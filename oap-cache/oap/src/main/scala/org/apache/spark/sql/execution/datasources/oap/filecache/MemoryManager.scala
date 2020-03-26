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

package org.apache.spark.sql.execution.datasources.oap.filecache

import java.io.File
import java.util.concurrent.atomic.AtomicLong

import org.apache.spark.SparkEnv
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.ConfigEntry
import org.apache.spark.memory.MemoryMode
import org.apache.spark.sql.execution.datasources.OapException
import org.apache.spark.sql.execution.datasources.oap.utils.PersistentMemoryConfigUtils
import org.apache.spark.sql.internal.oap.OapConf
import org.apache.spark.storage.{BlockManager, TestBlockId}
import org.apache.spark.unsafe.{PersistentMemoryPlatform, Platform, VMEMCacheJNI}
import org.apache.spark.util.Utils


object SourceEnum extends Enumeration {
  type SourceEnum = Value
  val DRAM, PM = Value
}

/**
 * A memory block holder which contains the base object, offset, length and occupiedSize. For DRAM
 * type of memory, the field of occupiedSize should be same as the block length. For Intel Optane
 * DC persistent memory, the occupied size is typically larger than length because the
 * heap management is based on the jemalloc.
 * @param baseObject null for OFF_HEAP or Intel Optane DC persistent memory
 * @param length the requested size of the block
 * @param occupiedSize the actual occupied size of the memory block
 */
case class MemoryBlockHolder(
                              baseObject: AnyRef,
                              baseOffset: Long,
                              length: Long,
                              occupiedSize: Long,
                              source: SourceEnum.SourceEnum)

private[sql] abstract class MemoryManager {
  /**
   * Return the total memory used until now.
   */
  def memoryUsed: Long

  /**
   * The memory size used for manager
   */
  def memorySize: Long

  /**
   * Allocate a block of memory with given size. The actual occupied size of memory maybe different
   * with the requested size, that's depends on the underlying implementation.
   * @param size requested size of memory block
   */
  private[filecache] def allocate(size: Long): MemoryBlockHolder
  private[filecache] def free(block: MemoryBlockHolder): Unit

  def stop(): Unit = {}

  def isDcpmmUsed(): Boolean = {false}
}

private[sql] object MemoryManager {
  /**
   * Dummy block id to acquire memory from [[org.apache.spark.memory.MemoryManager]]
   *
   * NOTE: We do acquire some memory from Spark without adding a Block into[[BlockManager]]
   * It may cause consistent problem.
   * (i.e. total size of blocks in BlockManager is not equal to Spark used storage memory)
   *
   * TODO should avoid using [[TestBlockId]]
   */
  private[filecache] val DUMMY_BLOCK_ID = TestBlockId("oap_memory_request_block")

  def apply(sparkEnv: SparkEnv): MemoryManager = {
    apply(sparkEnv, OapConf.OAP_FIBERCACHE_MEMORY_MANAGER)
  }

  def apply(sparkEnv: SparkEnv, configEntry: ConfigEntry[String]): MemoryManager = {
    val conf = sparkEnv.conf
    val memoryManagerOpt =
      conf.get(
        configEntry.key,
        configEntry.defaultValue.get).toLowerCase
    memoryManagerOpt match {
      case "offheap" => new OffHeapMemoryManager(sparkEnv)
      case "pm" => new PersistentMemoryManager(sparkEnv)
      case "hybrid" => new HybridMemoryManager(sparkEnv)
      case "tmp" => new TmpDramMemoryManager(sparkEnv)
      case _ => throw new UnsupportedOperationException(
        s"The memory manager: ${memoryManagerOpt} is not supported now")
    }
  }
}

/**
 * An memory manager which support allocate OFF_HEAP memory. It will acquire fixed amount of
 * memory from spark during initialization.
 */
private[filecache] class OffHeapMemoryManager(sparkEnv: SparkEnv)
  extends MemoryManager with Logging {

  private lazy val memoryManager = sparkEnv.memoryManager

  private lazy val oapMemory = {
    val offHeapSizeStr = sparkEnv.conf.get(OapConf.OAP_FIBERCACHE_OFFHEAP_MEMORY_SIZE).trim
    val offHeapSize = Utils.byteStringAsBytes(offHeapSizeStr)
    offHeapSize.toLong
  }

  // TODO: a config to control max memory size
  private val _memorySize = {
      oapMemory
  }

  // TODO: Atomic is really needed?
  private val _memoryUsed = new AtomicLong(0)

  override def memoryUsed: Long = _memoryUsed.get()

  override def memorySize: Long = _memorySize

  override private[filecache] def allocate(size: Long): MemoryBlockHolder = {
    val address = Platform.allocateMemory(size)
    _memoryUsed.getAndAdd(size)
    logDebug(s"request allocate $size memory, actual occupied size: " +
      s"${size}, used: $memoryUsed")
    // For OFF_HEAP, occupied size also equal to the size.
    MemoryBlockHolder(null, address, size, size, SourceEnum.DRAM)
  }

  override private[filecache] def free(block: MemoryBlockHolder): Unit = {
    assert(block.baseObject == null)
    Platform.freeMemory(block.baseOffset)
    _memoryUsed.getAndAdd(-block.occupiedSize)
    logDebug(s"freed ${block.occupiedSize} memory, used: $memoryUsed")
  }

  override def stop(): Unit = {
    memoryManager.releaseStorageMemory(oapMemory, MemoryMode.OFF_HEAP)
  }
}

/**
 * An memory manager which support allocate OFF_HEAP memory. It will not acquire memory from
 * spark storage memory, and cacheGuardian is consumer of this class.
 */
private[filecache] class TmpDramMemoryManager(sparkEnv: SparkEnv)
  extends MemoryManager with Logging {

  val cacheGuardianMemorySizeStr =
    sparkEnv.conf.get(OapConf.OAP_CACHE_GUARDIAN_MEMORY_SIZE)
  val cacheGuardianMemory = Utils.byteStringAsBytes(cacheGuardianMemorySizeStr)
  logInfo(s"cacheGuardian total use $cacheGuardianMemory bytes memory")

  private val _memoryUsed = new AtomicLong(0)
  override def memoryUsed: Long = _memoryUsed.get()
  override def memorySize: Long = cacheGuardianMemory

  override private[filecache] def allocate(size: Long): MemoryBlockHolder = {
    if (memoryUsed + size > cacheGuardianMemory) {
      throw new OapException("cache guardian use too much memory")
    }
    val startTime = System.currentTimeMillis()
    val occupiedSize = size
    val address = Platform.allocateMemory(occupiedSize)
    _memoryUsed.getAndAdd(occupiedSize)
    logDebug(s"memory manager allocate takes" +
      s" ${System.currentTimeMillis() - startTime} ms, " +
      s"request allocate $size memory, actual occupied size: " +
      s"${occupiedSize}, used: $memoryUsed")

    MemoryBlockHolder(null, address, size, occupiedSize, SourceEnum.DRAM)
  }

  override private[filecache] def free(block: MemoryBlockHolder): Unit = {
    val startTime = System.currentTimeMillis()
    assert(block.baseObject == null)
    Platform.freeMemory(block.baseOffset )
    _memoryUsed.getAndAdd(-block.occupiedSize)
    logDebug(s"memory manager free takes" +
      s" ${System.currentTimeMillis() - startTime} ms" +
      s"freed ${block.occupiedSize} memory, used: $memoryUsed")
  }
}

/**
 * A memory manager which supports allocate/free volatile memory from Intel Optane DC
 * persistent memory.
 */
private[filecache] class PersistentMemoryManager(sparkEnv: SparkEnv)
  extends MemoryManager with Logging {

  private val _memorySize = init()

  private val _memoryUsed = new AtomicLong(0)

  private def init(): Long = {
    val conf = sparkEnv.conf

    // The NUMA id should be set when the executor process start up. However, Spark don't
    // support NUMA binding currently.
    var numaId = conf.getInt("spark.executor.numa.id", -1)
    val executorId = sparkEnv.executorId.toInt
    val map = PersistentMemoryConfigUtils.parseConfig(conf)
    if (numaId == -1) {
      logWarning(s"Executor ${executorId} is not bind with NUMA. It would be better to bind " +
        s"executor with NUMA when cache data to Intel Optane DC persistent memory.")
      // Just round the executorId to the total NUMA number.
      // TODO: improve here
      numaId = executorId % PersistentMemoryConfigUtils.totalNumaNode(conf)
    }

    val initialPath = map.get(numaId).get
    val initialSizeStr = conf.get(OapConf.OAP_FIBERCACHE_PERSISTENT_MEMORY_INITIAL_SIZE).trim
    val initialSize = Utils.byteStringAsBytes(initialSizeStr)
    val reservedSizeStr = conf.get(OapConf.OAP_FIBERCACHE_PERSISTENT_MEMORY_RESERVED_SIZE).trim
    val reservedSize = Utils.byteStringAsBytes(reservedSizeStr)

    val enableConservative = conf.getBoolean(OapConf.OAP_ENABLE_MEMKIND_CONSERVATIVE.key,
      OapConf.OAP_ENABLE_MEMKIND_CONSERVATIVE.defaultValue.get)
    val memkindPattern = if (enableConservative) 1 else 0

    logInfo(s"Current Memkind pattern: ${memkindPattern}")

    val fullPath = Utils.createTempDir(initialPath + File.separator + executorId)
    PersistentMemoryPlatform.initialize(fullPath.getCanonicalPath, initialSize, memkindPattern)
    logInfo(s"Initialize Intel Optane DC persistent memory successfully, numaId: ${numaId}, " +
      s"initial path: ${fullPath.getCanonicalPath}, initial size: ${initialSize}, reserved size: " +
      s"${reservedSize}")
    require(reservedSize >= 0 && reservedSize < initialSize, s"Reserved size(${reservedSize}) " +
      s"should be larger than zero and smaller than initial size(${initialSize})")
    initialSize - reservedSize
  }

  override def memoryUsed: Long = _memoryUsed.get()

  override def memorySize: Long = _memorySize

  override private[filecache] def allocate(size: Long): MemoryBlockHolder = {
    try {
      val address = PersistentMemoryPlatform.allocateVolatileMemory(size)
      val occupiedSize = PersistentMemoryPlatform.getOccupiedSize(address)
      _memoryUsed.getAndAdd(occupiedSize)
      logDebug(s"request allocate $size memory, actual occupied size: " +
        s"${occupiedSize}, used: $memoryUsed")
      MemoryBlockHolder(null, address, size, occupiedSize, SourceEnum.PM)
    } catch {
      case e: OutOfMemoryError =>
        logWarning(e.getMessage)
        MemoryBlockHolder(null, 0L, 0L, 0L, SourceEnum.PM)
    }
  }

  override private[filecache] def free(block: MemoryBlockHolder): Unit = {
    assert(block.baseObject == null)
    PersistentMemoryPlatform.freeMemory(block.baseOffset)
    _memoryUsed.getAndAdd(-block.occupiedSize)
    logDebug(s"freed ${block.occupiedSize} memory, used: $memoryUsed")
  }

  override def isDcpmmUsed(): Boolean = {true}
}

private[filecache] class HybridMemoryManager(sparkEnv: SparkEnv)
  extends MemoryManager with Logging {
  private val (persistentMemoryManager, dramMemoryManager) =
    (new PersistentMemoryManager(sparkEnv), new TmpDramMemoryManager(sparkEnv))

  private val _memoryUsed = new AtomicLong(0)

  private var memBlockInPM = scala.collection.mutable.Set[MemoryBlockHolder]()

  private val (_dataDRAMCacheSize, _dataPMCacheSize, _dramGuardianSize, _pmGuardianSize) = init()

  var pmFull = false

  private def init(): (Long, Long, Long, Long) = {
    val conf = sparkEnv.conf
    // The NUMA id should be set when the executor process start up. However, Spark don't
    // support NUMA binding currently.
    var numaId = conf.getInt("spark.executor.numa.id", -1)
    val executorId = sparkEnv.executorId.toInt
    val map = PersistentMemoryConfigUtils.parseConfig(conf)
    if (numaId == -1) {
      logWarning(s"Executor ${executorId} is not bind with NUMA. It would be better to bind " +
        s"executor with NUMA when cache data to Intel Optane DC persistent memory.")
      // Just round the executorId to the total NUMA number.
      // TODO: improve here
      numaId = executorId % PersistentMemoryConfigUtils.totalNumaNode(conf)
    }

    val initialPath = map.get(numaId).get
    val initialSizeStr = conf.get(OapConf.OAP_FIBERCACHE_PERSISTENT_MEMORY_INITIAL_SIZE).trim
    val initialPMSize = Utils.byteStringAsBytes(initialSizeStr)
    val reservedSizeStr = conf.get(OapConf.OAP_FIBERCACHE_PERSISTENT_MEMORY_RESERVED_SIZE).trim
    val reservedPMSize = Utils.byteStringAsBytes(reservedSizeStr)
    val fullPath = Utils.createTempDir(initialPath + File.separator + executorId)
    val enableConservative = conf.getBoolean(OapConf.OAP_ENABLE_MEMKIND_CONSERVATIVE.key,
      OapConf.OAP_ENABLE_MEMKIND_CONSERVATIVE.defaultValue.get)
    val memkindPattern = if (enableConservative) 1 else 0
    PersistentMemoryPlatform.initialize(fullPath.getCanonicalPath, initialPMSize, memkindPattern)
    logInfo(s"Initialize Intel Optane DC persistent memory successfully, numaId: " +
      s"${numaId}, initial path: ${fullPath.getCanonicalPath}, initial size: " +
      s"${initialPMSize}, reserved size: ${reservedPMSize}")
    require(reservedPMSize >= 0 && reservedPMSize < initialPMSize,
      s"Reserved size(${reservedPMSize}) should be larger than zero and smaller than initial " +
        s"size(${initialPMSize})")
    val totalPMUsableSize = initialPMSize - reservedPMSize
    val initialDRAMSizeSize = Utils.byteStringAsBytes(
      conf.get(OapConf.OAP_FIBERCACHE_PERSISTENT_MEMORY_INITIAL_SIZE).trim)
    ((totalPMUsableSize * 0.9).toLong,
      (totalPMUsableSize * 0.9).toLong,
      (initialDRAMSizeSize * 0.9).toLong,
      (initialDRAMSizeSize * 0.1).toLong)
  }

  private val _memorySize = _dataPMCacheSize

  override def memorySize: Long = _memorySize

  override def memoryUsed: Long = _memoryUsed.get()

  override private[filecache] def allocate(size: Long) = {
    var memBlock: MemoryBlockHolder = null
    if (!pmFull) {
      memBlock = persistentMemoryManager.allocate(size)
      _memoryUsed.addAndGet(memBlock.occupiedSize)
      memBlockInPM += memBlock
      if (memBlock.length == 0) {
        pmFull = true
        memBlock = dramMemoryManager.allocate(size)
      }
    } else {
      memBlock = dramMemoryManager.allocate(size)
    }
  memBlock
  }

  override private[filecache] def free(block: MemoryBlockHolder): Unit = {
    if (memBlockInPM.contains(block)) {
      memBlockInPM.remove(block)
      persistentMemoryManager.free(block)
      _memoryUsed.addAndGet(-1 * block.occupiedSize)
    } else {
      dramMemoryManager.free(block)
    }
  }
}
