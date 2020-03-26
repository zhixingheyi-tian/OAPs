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

import org.mockito.Mockito.when

import org.apache.spark.Aggregator
import org.apache.spark.shuffle.BaseShuffleHandle
import org.apache.spark.shuffle.sort.SortShuffleWriter
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

object SortShuffleWriterBenchmark extends ShuffleWriterBenchmarkBase {

  private val shuffleHandle: BaseShuffleHandle[String, String, String] =
    new BaseShuffleHandle(
      shuffleId = 0,
      numMaps = 1,
      dependency = dependency)

  private val DATA_SIZE_LARGE = MAXIMUM_PAGE_SIZE_BYTES/4/DEFAULT_DATA_STRING_SIZE

  def getWriter(aggregator: Option[Aggregator[String, String, String]],
      sorter: Option[Ordering[String]]): SortShuffleWriter[String, String, String] = {
    // we need this since SortShuffleWriter uses SparkEnv to get lots of its private vars
    setEnvAndContext()

    if (aggregator.isEmpty && sorter.isEmpty) {
      when(dependency.mapSideCombine).thenReturn(false)
    } else {
      when(dependency.mapSideCombine).thenReturn(false)
      when(dependency.aggregator).thenReturn(aggregator)
      when(dependency.keyOrdering).thenReturn(sorter)
    }

    new SortShuffleWriter[String, String, String](
      blockResolver,
      shuffleHandle,
      0,
      taskContext)
  }

  def getRemoteWriter(aggregator: Option[Aggregator[String, String, String]],
      sorter: Option[Ordering[String]]): RemoteShuffleWriter[String, String, String] = {
    // we need this since SortShuffleWriter uses SparkEnv to get lots of its private vars
    setEnvAndContext()

    if (aggregator.isEmpty && sorter.isEmpty) {
      when(dependency.mapSideCombine).thenReturn(false)
    } else {
      when(dependency.mapSideCombine).thenReturn(false)
      when(dependency.aggregator).thenReturn(aggregator)
      when(dependency.keyOrdering).thenReturn(sorter)
    }

    new RemoteShuffleWriter[String, String, String](
      blockResolverRemote,
      shuffleHandle,
      0,
      taskContext)
  }

  def writeBenchmarkWithSmallDataset(): Unit = {
    val size = DATA_SIZE_SMALL
    val benchmark = new Benchmark("SortShuffleWriter without spills",
      size,
      minNumIters = MIN_NUM_ITERS,
      output = output)
    addBenchmarkCaseFor2Writers(benchmark,
      "small dataset without spills",
      size,
      () => getWriter(Option.empty, Option.empty),
      () => getRemoteWriter(Option.empty, Option.empty),
      Some(0))
    benchmark.run()
  }

  def writeBenchmarkWithSpill(): Unit = {
    for (size <- Seq(DATA_SIZE_MIDDLE, DATA_SIZE_LARGE)) {
      val benchmark = new Benchmark("SortShuffleWriter with spills",
        size,
        minNumIters = MIN_NUM_ITERS,
        output = output,
        outputPerIteration = true)
      addBenchmarkCaseFor2Writers(benchmark,
        s"no map side combine: $size rows",
        size,
        () => getWriter(Option.empty, Option.empty),
        () => getRemoteWriter(Option.empty, Option.empty),
        Some(7))

      def createCombiner(i: String): String = i
      def mergeValue(i: String, j: String): String = if (Ordering.String.compare(i, j) > 0) i else j
      def mergeCombiners(i: String, j: String): String =
        if (Ordering.String.compare(i, j) > 0) i else j
      val aggregator =
        new Aggregator[String, String, String](createCombiner, mergeValue, mergeCombiners)
      addBenchmarkCaseFor2Writers(benchmark,
        s"with map side aggregation: $size rows",
        size,
        () => getWriter(Some(aggregator), Option.empty),
        () => getRemoteWriter(Some(aggregator), Option.empty),
        Some(7))

      val sorter = Ordering.String
      addBenchmarkCaseFor2Writers(benchmark,
        s"with map side sort: $size rows",
        size,
        () => getWriter(Option.empty, Some(sorter)),
        () => getRemoteWriter(Option.empty, Some(sorter)),
        Some(7))
      benchmark.run()
    }
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    runBenchmark("SortShuffleWriter writer") {
      writeBenchmarkWithSmallDataset()
      writeBenchmarkWithSpill()
    }
  }
}
