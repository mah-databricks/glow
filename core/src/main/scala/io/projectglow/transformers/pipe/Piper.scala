/*
 * Copyright 2019 The Glow Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.projectglow.transformers.pipe

import java.io._
import java.util.concurrent.atomic.AtomicReference

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.io.Source

import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SQLUtils, SparkSession}
import org.apache.spark.storage.StorageLevel

import io.projectglow.common.{GlowLogging, WithUtils}

/**
 * Based on Spark's PipedRDD with the following modifications:
 * - Act only on DataFrames instead of generic RDDs
 * - Use the input and output formatters to determine output schema
 * - Use the input and output formatters to return a DataFrame
 */
private[projectglow] object Piper extends GlowLogging {
  private val cachedRdds = mutable.ListBuffer[RDD[_]]()

  def clearCache(): Unit = cachedRdds.synchronized {
    SparkSession.getActiveSession match {
      case None => // weird
      case Some(spark) =>
        cachedRdds.foreach { rdd =>
          if (rdd.sparkContext == spark.sparkContext) {
            rdd.unpersist()
          }
        }
    }
    cachedRdds.clear()
  }

  // Pipes a single row of the input DataFrame to get the output schema before piping all of it.
  def pipe(
      informatter: InputFormatter,
      outputformatter: OutputFormatter,
      cmd: Seq[String],
      env: Map[String, String],
      df: DataFrame,
      quarantineLocation: Option[String] = None): DataFrame = {
    logger.info(s"Beginning pipe with cmd $cmd")

    val quarantineInfo = quarantineLocation.map(
      PipeIterator.QuarantineInfo(df, _)
    )
    val rawRdd = df.queryExecution.toRdd
    val inputRdd = if (rawRdd.getNumPartitions == 0) {
      logger.warn("Not piping any rows, as the input DataFrame has zero partitions.")
      SQLUtils.createEmptyRDD(df.sparkSession)
    } else {
      rawRdd
    }

    // Each partition consists of an iterator with the schema, followed by [[InternalRow]]s with the
    // schema
    val schemaInternalRowRDD = inputRdd.mapPartitions { it =>
      if (it.isEmpty) {
        Iterator.empty
      } else {
        new PipeIterator(cmd, env, it, informatter, outputformatter, quarantineInfo)
      }
    }.persist(StorageLevel.DISK_ONLY)

    cachedRdds.synchronized {
      cachedRdds.append(schemaInternalRowRDD)
    }

    val schemaSeq = schemaInternalRowRDD.mapPartitions { it =>
      if (it.hasNext) {
        Iterator(it.next.asInstanceOf[StructType])
      } else {
        Iterator.empty
      }
    }.collect.distinct

    if (schemaSeq.length != 1) {
      throw new IllegalStateException(
        s"Cannot infer schema: saw ${schemaSeq.length} distinct schemas.")
    }

    val schema = schemaSeq.head
    val internalRowRDD = schemaInternalRowRDD.mapPartitions { it =>
      it.drop(1).asInstanceOf[Iterator[InternalRow]]
    }

    SQLUtils.internalCreateDataFrame(df.sparkSession, internalRowRDD, schema, isStreaming = false)
  }
}

private[projectglow] class ProcessHelper(
    cmd: Seq[String],
    environment: Map[String, String],
    inputFn: OutputStream => Unit,
    context: TaskContext)
    extends GlowLogging {

  private val _childThreadException = new AtomicReference[Throwable](null)
  private var process: Process = _

  def startProcess(): BufferedInputStream = {

    val pb = new ProcessBuilder(cmd.asJava)
    val pbEnv = pb.environment()
    environment.foreach { case (k, v) => pbEnv.put(k, v) }
    process = pb.start()

    val stdinWriterThread = new Thread(s"${ProcessHelper.STDIN_WRITER_THREAD_PREFIX} for $cmd") {
      override def run(): Unit = {
        SQLUtils.setTaskContext(context)
        val out = process.getOutputStream
        try {
          inputFn(out)
        } catch {
          case t: Throwable => _childThreadException.set(t)
        } finally {
          out.close()
        }
      }
    }
    stdinWriterThread.start()

    val stderrReaderThread = new Thread(s"${ProcessHelper.STDERR_READER_THREAD_PREFIX} for $cmd") {
      override def run(): Unit = {
        val err = process.getErrorStream
        try {
          for (line <- Source.fromInputStream(err).getLines) {
            logger.info(s"Got stderr line")
            // scalastyle:off println
            System.err.println(line)
            // scalastyle:on println
          }
        } catch {
          case t: Throwable => _childThreadException.set(t)
        } finally {
          err.close()
        }
      }
    }
    stderrReaderThread.start()

    new BufferedInputStream(process.getInputStream)
  }

  def waitForProcess(): Int = {
    if (process == null) {
      throw new IllegalStateException(s"Process hasn't been started yet")
    }
    process.waitFor()
  }

  def propagateChildException(): Unit = {
    val t = _childThreadException.get()
    if (t != null) {
      Option(process).foreach(_.destroy())
      throw t
    }
  }

  def childThreadException: Option[Throwable] = Option(_childThreadException.get())
}

object ProcessHelper {
  val STDIN_WRITER_THREAD_PREFIX = "stdin writer"
  val STDERR_READER_THREAD_PREFIX = "stderr reader"
}

class PipeIterator(
    cmd: Seq[String],
    environment: Map[String, String],
    _input: Iterator[InternalRow],
    inputFormatter: InputFormatter,
    outputFormatter: OutputFormatter,
    quarantineInfo: Option[PipeIterator.QuarantineInfo])
    extends Iterator[Any] {

  private val input = _input.toSeq
  private val processHelper = new ProcessHelper(cmd, environment, writeInput, TaskContext.get)
  private val inputStream = processHelper.startProcess()
  private val baseIterator = outputFormatter.makeIterator(inputStream)

  private def writeInput(stream: OutputStream): Unit = {
    WithUtils.withCloseable(inputFormatter) { informatter =>
      informatter.init(stream)
      input.foreach(informatter.write)
    }
  }

  override def hasNext: Boolean = {
    val result = if (baseIterator.hasNext) {
      true
    } else {
      val exitStatus = processHelper.waitForProcess()
      if (exitStatus != 0) {
        quarantineInfo.foreach { quarantineInfo =>
          val thrownFromProcess =
            processHelper.childThreadException.getOrElse(new Throwable("unknown"))
          PipeIterator.quarantine(exitStatus, thrownFromProcess, input, quarantineInfo)
        }
        throw new IllegalStateException(s"Subprocess exited with status $exitStatus")
      }
      false
    }
    processHelper.propagateChildException()
    result
  }

  override def next(): Any = baseIterator.next()
}

object PipeIterator {
  def quarantine(status: Int, th: Throwable, data: Seq[InternalRow], qi: QuarantineInfo): Unit =
    SparkSession.getActiveSession.foreach { spark =>
      qi.df.write.format("delta").mode("append").saveAsTable(qi.location)
    }

  /* ~~~Scalastyle template evidently does not accept standard scaladoc comments~~~
   * ~~~Scalastyle states "Insert a space after the start of the comment"       ~~~
   * Data for Quarantining records which fail in process.
   * @param df The [[DataFrame]] being processed.
   * @param location The delta table to write to. Typically of the form `classifier.tableName`.
   */
  final case class QuarantineInfo(df: DataFrame, location: String)
}
