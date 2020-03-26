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

import java.io.File
import java.util.UUID

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

import org.apache.commons.io.FileUtils
import org.mockito._
import org.mockito.Answers.RETURNS_SMART_NULLS
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when

import org.apache.spark._
import org.apache.spark.benchmark.BenchmarkBase
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.memory.{MemoryManager, TaskMemoryManager, TestMemoryManager}
import org.apache.spark.rpc.{RpcEndpoint, RpcEndpointRef, RpcEnv}
import org.apache.spark.serializer.{KryoSerializer, Serializer, SerializerManager}
import org.apache.spark.shuffle.{IndexShuffleBlockResolver, ShuffleWriter}
import org.apache.spark.storage.{BlockManager, DiskBlockManager, TempShuffleBlockId}
import org.apache.spark.util.{Benchmark, Utils}


abstract class ShuffleWriterBenchmarkBase extends BenchmarkBase {

  protected val MIN_NUM_ITERS = 10
  protected val DATA_SIZE_SMALL = 1000
  protected val DATA_SIZE_MIDDLE = 100000

  protected val DEFAULT_DATA_STRING_SIZE = 5

  // This is only used in the writer constructors, so it's ok to mock
  @Mock(answer = RETURNS_SMART_NULLS) protected var dependency:
  ShuffleDependency[String, String, String] = _
  // This is only used in the stop() function, so we can safely mock this without affecting perf
  @Mock(answer = RETURNS_SMART_NULLS) protected var taskContext: TaskContext = _
  @Mock(answer = RETURNS_SMART_NULLS) protected var rpcEnv: RpcEnv = _
  @Mock(answer = RETURNS_SMART_NULLS) protected var rpcEndpointRef: RpcEndpointRef = _

  protected  val serializer: Serializer = new KryoSerializer(defaultConf)
  protected val partitioner: HashPartitioner = new HashPartitioner(10)
  protected val serializerManager: SerializerManager =
    new SerializerManager(serializer, defaultConf)
  protected val shuffleMetrics: TaskMetrics = new TaskMetrics

  protected val tempFilesCreated: ArrayBuffer[File] = new ArrayBuffer[File]
  protected val filenameToFile: mutable.Map[String, File] = new mutable.HashMap[String, File]

  class TestDiskBlockManager(tempDir: File) extends DiskBlockManager(defaultConf, false) {
    override def getFile(filename: String): File = {
      if (filenameToFile.contains(filename)) {
        filenameToFile(filename)
      } else {
        val outputFile = File.createTempFile("shuffle", null, tempDir)
        filenameToFile(filename) = outputFile
        outputFile
      }
    }

    override def createTempShuffleBlock(): (TempShuffleBlockId, File) = {
      var blockId = new TempShuffleBlockId(UUID.randomUUID())
      val file = getFile(blockId)
      tempFilesCreated += file
      (blockId, file)
    }
  }

  class TestBlockManager(tempDir: File, memoryManager: MemoryManager) extends BlockManager("0",
    rpcEnv,
    null,
    serializerManager,
    defaultConf,
    memoryManager,
    null,
    null,
    null,
    null,
    1) {
    override val diskBlockManager = new TestDiskBlockManager(tempDir)
    override val remoteBlockTempFileManager = null
  }

  protected var tempDir: File = _

  protected var blockManager: BlockManager = _
  protected var blockResolver: IndexShuffleBlockResolver = _
  protected var blockResolverRemote: RemoteShuffleBlockResolver = _

  protected var memoryManager: TestMemoryManager = _
  protected var taskMemoryManager: TaskMemoryManager = _

  MockitoAnnotations.initMocks(this)
  when(dependency.partitioner).thenReturn(partitioner)
  when(dependency.serializer).thenReturn(serializer)
  when(dependency.shuffleId).thenReturn(0)
  when(taskContext.taskMetrics()).thenReturn(shuffleMetrics)
  when(rpcEnv.setupEndpoint(any[String], any[RpcEndpoint])).thenReturn(rpcEndpointRef)

  protected def setEnvAndContext(): Unit = {
    when(taskContext.taskMemoryManager()).thenReturn(taskMemoryManager)
    TaskContext.setTaskContext(taskContext)
  }

  def setup(): Unit = {
    TaskContext.setTaskContext(taskContext)
    memoryManager = new TestMemoryManager(defaultConf)
    memoryManager.limit(MAXIMUM_PAGE_SIZE_BYTES)
    taskMemoryManager = new TaskMemoryManager(memoryManager, 0)
    tempDir = Utils.createTempDir()
    blockManager = new TestBlockManager(tempDir, memoryManager)
    blockResolver = new IndexShuffleBlockResolver(
      defaultConf,
      blockManager)
    blockResolverRemote = new RemoteShuffleBlockResolver(defaultConfRemote)
  }

  // Some configuration may only apply to one single shuffle writer, for example, no
  // RemoteShuffleWriters support transferTo currently.
  protected def addBenchmarkCaseForSingleWriter(
      benchmark: Benchmark,
      name: String,
      size: Int,
      writerSupplierInner: () => ShuffleWriter[String, String],
      numSpillFiles: Option[Int] = Option.empty): Unit = {
    benchmark.addTimerCase(name) { timer =>
      setup()
      val writer = writerSupplierInner()
      val dataIterator = createDataIterator(size)
      try {
        timer.startTiming()
        writer.write(dataIterator)
        timer.stopTiming()
      } finally {
        writer.stop(true)
      }
      teardown()
    }
  }

  def addBenchmarkCaseFor2Writers(
      benchmark: Benchmark,
      name: String,
      size: Int,
      writerSupplier: () => ShuffleWriter[String, String],
      writerSupplierRemote: () => ShuffleWriter[String, String],
      numSpillFiles: Option[Int] = Option.empty): Unit = {
    addBenchmarkCaseForSingleWriter(benchmark, name, size, writerSupplier, numSpillFiles)
    addBenchmarkCaseForSingleWriter(
      benchmark, s"$name(R)", size, writerSupplierRemote, numSpillFiles)
  }

  def teardown(): Unit = {
    FileUtils.deleteDirectory(tempDir)
    tempFilesCreated.clear()
    filenameToFile.clear()
  }

  protected class DataIterator (size: Int)
      extends Iterator[Product2[String, String]] {
    val random = new Random(123)
    var count = 0
    override def hasNext: Boolean = {
      count < size
    }

    override def next(): Product2[String, String] = {
      count+=1
      val string = random.alphanumeric.take(DEFAULT_DATA_STRING_SIZE).mkString
      (string, string)
    }
  }

  def createDataIterator(size: Int): DataIterator = {
    new DataIterator(size)
  }

}
