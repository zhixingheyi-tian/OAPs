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

package org.apache.spark.benchmark

import java.io.{File, FileOutputStream, OutputStream}
import java.util.UUID

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.shuffle.remote.createDefaultConf

/**
  * A base class for generate benchmark results to a file.
  */
abstract class BenchmarkBase {
  // From PackedRecordPointer
  protected val MAXIMUM_PAGE_SIZE_BYTES: Int = 1 << 27

  var output: Option[OutputStream] = None

  protected val defaultConf: SparkConf = new SparkConf(loadDefaults = false)
  protected val defaultConfRemote: SparkConf =
    createDefaultConf(loadDefaults = true).set("spark.app.id", s"test_${UUID.randomUUID()}")
  var sc: SparkContext = _

  /**
    * Main process of the whole benchmark.
    * Implementations of this method are supposed to use the wrapper method `runBenchmark`
    * for each benchmark scenario.
    */
  def runBenchmarkSuite(mainArgs: Array[String]): Unit

  final def runBenchmark(benchmarkName: String)(func: => Any): Unit = {
    val separator = "=" * 96
    val testHeader = (separator + '\n' + benchmarkName + '\n' + separator + '\n' + '\n').getBytes
    output.foreach(_.write(testHeader))
    func
    output.foreach(_.write('\n'))
  }

  def beforeAll(): Unit = {
    sc = new SparkContext("local[1]", "shuffle_writer", defaultConfRemote)
  }

  def main(args: Array[String]): Unit = {

    beforeAll()

    val regenerateBenchmarkFiles: Boolean = System.getenv("SPARK_GENERATE_BENCHMARK_FILES") == "1"
    if (regenerateBenchmarkFiles) {
      val resultFileName = s"${this.getClass.getSimpleName.replace("$", "")}-results.txt"
      val file = new File(s"benchmarks/$resultFileName")
      if (!file.exists()) {
        file.getParentFile().mkdirs();
        file.createNewFile()
      }
      output = Some(new FileOutputStream(file))
    }

    runBenchmarkSuite(args)

    output.foreach { o =>
      if (o != null) {
        o.close()
      }
    }

    afterAll()
  }

  /**
    * Any shutdown code to ensure a clean shutdown
    */
  def afterAll(): Unit = {
    if (sc != null) {
      sc.stop()
    }
  }
}
