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

package org.apache.spark.shuffle.sort

import org.apache.spark.SparkConf
import org.apache.spark.shuffle.remote.ShuffleWriterBenchmarkBase
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
object BypassMergeSortShuffleWriterBenchmark extends ShuffleWriterBenchmarkBase {

  private val shuffleHandle: BypassMergeSortShuffleHandle[String, String] =
    new BypassMergeSortShuffleHandle[String, String](
      shuffleId = 0,
      numMaps = 1,
      dependency)

  private val DATA_SIZE_LARGE = MAXIMUM_PAGE_SIZE_BYTES/4/DEFAULT_DATA_STRING_SIZE

  def getWriter(transferTo: Boolean): BypassMergeSortShuffleWriter[String, String] = {
    val conf = new SparkConf(loadDefaults = false)
    conf.set("spark.file.transferTo", String.valueOf(transferTo))
    conf.set("spark.shuffle.file.buffer", "32k")

    val shuffleWriter = new BypassMergeSortShuffleWriter[String, String](
      blockManager,
      blockResolver,
      shuffleHandle,
      0,
      taskContext,
      conf)

    shuffleWriter
  }

  def getRemoteWriter(transferTo: Boolean): RemoteBypassMergeSortShuffleWriter[String, String] = {
    val conf = new SparkConf(loadDefaults = false)
    conf.set("spark.file.transferTo", String.valueOf(transferTo))
    conf.set("spark.shuffle.file.buffer", "32k")

    setEnvAndContext()

    val shuffleWriter = new RemoteBypassMergeSortShuffleWriter[String, String](
      blockManager,
      blockResolverRemote,
      shuffleHandle,
      0,
      taskContext,
      conf)

    shuffleWriter
  }

  def writeBenchmarkWithLargeDataset(): Unit = {
    for (size <- Seq(DATA_SIZE_SMALL, DATA_SIZE_LARGE)) {
      val benchmark = new Benchmark(
        s"BypassMergeSortShuffleWrite with spill",
        size,
        minNumIters = MIN_NUM_ITERS,
        output = output)

      addBenchmarkCaseFor2Writers(benchmark,
        s"without transferTo: $size rows",
        size,
        () => getWriter(false),
        () => getRemoteWriter(false))

      addBenchmarkCaseForSingleWriter(benchmark,
        s"with transferTo: $size rows",
        size, () => getWriter(true))
      benchmark.run()
    }
  }

  def writeBenchmarkWithSmallDataset(): Unit = {
    val size = DATA_SIZE_SMALL
    val benchmark = new Benchmark("BypassMergeSortShuffleWrite without spill",
      size,
      minNumIters = MIN_NUM_ITERS,
      output = output)
    addBenchmarkCaseFor2Writers(benchmark,
      "small dataset without disk spill",
      size,
      () => getWriter(false),
      () => getRemoteWriter(false))
    benchmark.run()
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    runBenchmark("BypassMergeSortShuffleWriter write") {
      writeBenchmarkWithSmallDataset()
      writeBenchmarkWithLargeDataset()
    }
  }
}
