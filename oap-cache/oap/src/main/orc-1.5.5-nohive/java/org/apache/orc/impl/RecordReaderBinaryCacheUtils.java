package org.apache.orc.impl;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;
import org.apache.orc.DataReader;
import org.apache.orc.storage.common.io.DiskRangeList;
import org.apache.spark.sql.execution.datasources.oap.filecache.FiberCache;
import org.apache.spark.sql.execution.datasources.oap.filecache.FiberCacheManager;
import org.apache.spark.sql.execution.datasources.oap.filecache.OrcBinaryFiberId;
import org.apache.spark.sql.execution.datasources.oap.io.DataFile;
import org.apache.spark.sql.execution.datasources.oap.io.OrcDataFile;
import org.apache.spark.sql.oap.OapRuntime$;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.unsafe.Platform;

import java.io.IOException;
import java.nio.ByteBuffer;

public class RecordReaderBinaryCacheUtils extends RecordReaderUtils {

  protected static class BinaryCacheDataReader extends DefaultDataReader {
    private BinaryCacheDataReader(DataReaderProperties properties) {
      super(properties);
    }

    public DiskRangeList readFileColumnData(
            DiskRangeList range, long baseOffset, boolean doForceDirect) throws IOException {
      return RecordReaderBinaryCacheUtils.readColumnRanges(file, path, baseOffset, range, doForceDirect);
    }
  }

  public static DataReader createBinaryCacheDataReader(DataReaderProperties properties) {
    return new BinaryCacheDataReader(properties);
  }

  /**
   * Read the list of ranges from the file.
   * @param file the file to read
   * @param base the base of the stripe
   * @param range the disk ranges within the stripe to read
   * @return the bytes read for each disk range, which is the same length as
   *    ranges
   * @throws IOException
   */
  static DiskRangeList readColumnRanges(FSDataInputStream file,
                                        Path path,
                                        long base,
                                        DiskRangeList range,
                                        boolean doForceDirect) throws IOException {
    if (range == null) return null;
    DiskRangeList prev = range.prev;
    if (prev == null) {
      prev = new DiskRangeList.MutateHelper(range);
    }
    while (range != null) {
      if (range.hasData()) {
        range = range.next;
        continue;
      }
      int len = (int) (range.getEnd() - range.getOffset());
      long off = range.getOffset();
      // Don't use HDFS ByteBuffer API because it has no readFully, and is buggy and pointless.

      byte[] buffer = new byte[len];

      DataFile dataFile = new OrcDataFile(path.toUri().toString(),
              new StructType(), new Configuration());
      FiberCacheManager cacheManager = OapRuntime$.MODULE$.getOrCreate().fiberCacheManager();
      FiberCache fiberCache = null;
      OrcBinaryFiberId fiberId = null;
      ColumnDiskRangeList columnRange = (ColumnDiskRangeList)range;
      fiberId = new OrcBinaryFiberId(dataFile, columnRange.columnId, columnRange.currentStripe);
      fiberId.withLoadCacheParameters(file, base + off, len);
      fiberCache = cacheManager.get(fiberId);
      long fiberOffset = fiberCache.getBaseOffset();
      Platform.copyMemory(null, fiberOffset, buffer, Platform.BYTE_ARRAY_OFFSET, len);

      ByteBuffer bb = null;
      if (doForceDirect) {
        bb = ByteBuffer.allocateDirect(len);
        bb.put(buffer);
        bb.position(0);
        bb.limit(len);
      } else {
        bb = ByteBuffer.wrap(buffer);
      }
      range = range.replaceSelfWith(new BufferChunk(bb, range.getOffset()));
      range = range.next;
    }
    return prev.next;
  }

}
