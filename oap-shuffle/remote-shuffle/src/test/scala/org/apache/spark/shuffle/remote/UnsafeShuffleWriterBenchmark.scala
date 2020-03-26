/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.shuffle.remote

import org.apache.spark.{SparkConf, TaskContext}
import org.apache.spark.shuffle.sort.{RemoteUnsafeShuffleWriter, SerializedShuffleHandle, UnsafeShuffleWriter}
import org.apache.spark.util.Benchmark

/**
 * Benchmark to measure performance for aggregate primitives.
 * {{{
 *   To run this benchmark:
 *   1. without sbt: bin/spark-submit --class <this class> <spark sql test jar>
 *   2. build/sbt "sql/test:runMain <this class>"
 *   3. generate result: SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/test:runMain <this class>"
 *      Results will be written to "benchmarks/<this class>-results.txt".
 * }}}
 */
object UnsafeShuffleWriterBenchmark extends ShuffleWriterBenchmarkBase {

  private val shuffleHandle: SerializedShuffleHandle[String, String] =
    new SerializedShuffleHandle[String, String](0, 0, this.dependency)

  private val DATA_SIZE_LARGE = MAXIMUM_PAGE_SIZE_BYTES/2/DEFAULT_DATA_STRING_SIZE

  def getWriter(transferTo: Boolean): UnsafeShuffleWriter[String, String] = {
    val conf = new SparkConf(loadDefaults = false)
    conf.set("spark.file.transferTo", String.valueOf(transferTo))

    TaskContext.setTaskContext(taskContext)
    new UnsafeShuffleWriter[String, String](
      blockManager,
      blockResolver,
      taskMemoryManager,
      shuffleHandle,
      0,
      taskContext,
      conf)
  }

  def getRemoteWriter(transferTo: Boolean): RemoteUnsafeShuffleWriter[String, String] = {
    val conf = new SparkConf(loadDefaults = false)
    conf.set("spark.file.transferTo", String.valueOf(transferTo))

    setEnvAndContext()
    new RemoteUnsafeShuffleWriter[String, String](
      blockManager,
      blockResolverRemote,
      taskMemoryManager,
      shuffleHandle,
      0,
      taskContext,
      conf)
  }


  def writeBenchmarkWithSmallDataset(): Unit = {
    val size = DATA_SIZE_SMALL
    val benchmark = new Benchmark("UnsafeShuffleWriter without spills",
      size,
      minNumIters = MIN_NUM_ITERS,
      output = output)
    addBenchmarkCaseFor2Writers(benchmark,
      "small dataset without spills",
      size,
      () => getWriter(false),
      () => getRemoteWriter(false),
      Some(1)) // The single temp file is for the temp index file
    benchmark.run()
  }

  def writeBenchmarkWithSpill(): Unit = {
    for (size <- Seq(DATA_SIZE_MIDDLE, DATA_SIZE_LARGE)) {
      val benchmark = new Benchmark("UnsafeShuffleWriter with spills",
        size,
        minNumIters = MIN_NUM_ITERS,
        output = output,
        outputPerIteration = true)
      addBenchmarkCaseFor2Writers(benchmark,
        s"without transferTo: $size rows",
        size,
        () => getWriter(false),
        () => getRemoteWriter(false),
        Some(7))
      addBenchmarkCaseForSingleWriter(benchmark,
        s"with transferTo: $size rows",
        size,
        () => getWriter(true),
        Some(7))
      benchmark.run()
    }
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    runBenchmark("UnsafeShuffleWriter write") {
      writeBenchmarkWithSmallDataset()
      writeBenchmarkWithSpill()
    }
  }
}
