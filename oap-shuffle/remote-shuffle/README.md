# Spark Remote Shuffle Plugin

Remote Shuffle is a Spark ShuffleManager plugin, writing shuffle files to a remote Hadoop-compatible file system, instead of vanilla Spark's local disks.

This is essential to disaggregated compute and storage architecture.

## Configuration

```
spark.files                             file:///path/to/remote-shuffle-dir/remote-shuffle-<version>.jar
spark.executor.extraClassPath           ./remote-shuffle-<version>.jar
spark.driver.extraClassPath             /path/to/remote-shuffle-dir/remote-shuffle-<version>.jar
spark.shuffle.manager                   org.apache.spark.shuffle.remote.RemoteShuffleManager
spark.shuffle.remote.storageMasterUri   hdfs://namenode:port
```

## Performance tuning

### Index files cache

Instead of reading index files directly from storage, a reduce task fetches them from the cache on the executors who wrote the map outputs. This feature reduces small network I/Os between the compute and storage clusters, disk I/Os on the storage cluster, and better utilizes the network inside compute cluster.
```
spark.shuffle.remote.index.cache.size    20m
```
