# nf-iceberg

Apache Iceberg-based global cache plugin for Nextflow.

## Overview

This plugin provides a distributed, ACID-compliant cache using [Apache Iceberg](https://iceberg.apache.org/), enabling:

- **Global cache sharing** across pipelines, users, and environments
- **ACID transactions** for safe concurrent writes from multiple pipelines
- **Time travel** for cache history, debugging, and recovery
- **Cloud-native storage** optimized for S3, GCS, and Azure Blob Storage
- **Content-addressable deduplication** to minimize storage costs

## Why Iceberg for Caching?

Traditional Nextflow caching is local or session-specific. For large organizations running many pipelines, this means:
- Redundant computation across teams
- Wasted cloud compute costs
- No ability to share cached results

Iceberg solves this by providing:

| Feature | Benefit |
|---------|---------|
| ACID transactions | Multiple pipelines can write simultaneously |
| Snapshot isolation | Read consistent data while others write |
| Time travel | Recover from accidental cache corruption |
| Partition evolution | Optimize cache layout without rewrites |
| Schema evolution | Future-proof cache structure |

## Configuration

### Basic Setup

```groovy
// nextflow.config
iceberg {
    warehouse = 's3://my-bucket/nextflow-cache'
}

plugins {
    id 'nf-iceberg'
}
```

### Full Configuration

```groovy
iceberg {
    // Required: Warehouse location (S3, GCS, HDFS, or local)
    warehouse = 's3://my-bucket/nextflow-cache'
    
    // Catalog type: 'hadoop' (default), 'glue', 'rest', 'jdbc'
    catalog = 'hadoop'
    
    // Iceberg namespace for cache tables
    namespace = 'nextflow_cache'
    
    // Table names
    tableName = 'task_cache'
    indexTableName = 'task_index'
    
    // Catalog-specific properties
    catalogProperties = [
        'io-impl': 'org.apache.iceberg.aws.s3.S3FileIO'
    ]
    
    // Cache behavior
    globalScope = true           // Share cache across all runs
    ttlDays = 30                 // Expire entries after 30 days (0 = infinite)
    deduplication = true         // Content-addressable storage
    compression = 'zstd'         // Compression: 'zstd', 'snappy', 'gzip', 'none'
    
    // Reliability
    maxRetries = 3               // Retries for transient failures
    snapshotRetention = 5        // Snapshots to retain for time travel
}
```

### Environment Variables

```bash
# Minimal setup via environment
export NXF_ICEBERG_WAREHOUSE=s3://my-bucket/nextflow-cache
export NXF_ICEBERG_CATALOG=hadoop
export NXF_ICEBERG_NAMESPACE=nextflow_cache
```

## Cloud Provider Setup

### AWS S3

```groovy
iceberg {
    warehouse = 's3://my-bucket/nextflow-cache'
    catalogProperties = [
        'io-impl': 'org.apache.iceberg.aws.s3.S3FileIO',
        's3.access-key-id': 'YOUR_ACCESS_KEY',      // Optional if using IAM roles
        's3.secret-access-key': 'YOUR_SECRET_KEY'   // Optional if using IAM roles
    ]
}
```

### AWS Glue Catalog

```groovy
iceberg {
    warehouse = 's3://my-bucket/nextflow-cache'
    catalog = 'glue'
    catalogProperties = [
        'glue.region': 'us-east-1'
    ]
}
```

### Google Cloud Storage

```groovy
iceberg {
    warehouse = 'gs://my-bucket/nextflow-cache'
    catalogProperties = [
        'io-impl': 'org.apache.iceberg.gcp.gcs.GCSFileIO'
    ]
}
```

### Local Development

```groovy
iceberg {
    warehouse = '/tmp/nextflow-cache'
    catalog = 'hadoop'
}
```

## Usage

### Run with Iceberg Cache

```bash
nextflow run my-pipeline.nf -plugins nf-iceberg
```

### Resume from Cache

```bash
# Resume uses cached results from the global Iceberg table
nextflow run my-pipeline.nf -plugins nf-iceberg -resume
```

### Time Travel (Recovery)

```groovy
// In your pipeline or config, you can access cache history
// (Advanced: requires custom scripting)
```

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     Nextflow Pipeline                         │
├──────────────────────────────────────────────────────────────┤
│                    nf-iceberg Plugin                          │
│  ┌─────────────────┐  ┌─────────────────┐                    │
│  │ IcebergCache    │  │ IcebergCache    │                    │
│  │ Factory         │  │ Store           │                    │
│  └────────┬────────┘  └────────┬────────┘                    │
│           │                    │                              │
│           ▼                    ▼                              │
│  ┌─────────────────────────────────────────┐                 │
│  │         Apache Iceberg                   │                 │
│  │  ┌───────────┐  ┌───────────────────┐   │                 │
│  │  │ Catalog   │  │ Table Operations  │   │                 │
│  │  │ (Hadoop/  │  │ (ACID writes,     │   │                 │
│  │  │  Glue/    │  │  time travel)     │   │                 │
│  │  │  REST)    │  │                   │   │                 │
│  │  └───────────┘  └───────────────────┘   │                 │
│  └─────────────────────────────────────────┘                 │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│                    Object Storage                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                   │
│  │   S3     │  │   GCS    │  │  Azure   │                   │
│  │          │  │          │  │  Blob    │                   │
│  └──────────┘  └──────────┘  └──────────┘                   │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ nextflow_cache/                                          │ │
│  │ ├── task_cache/           # Cached task data             │ │
│  │ │   ├── metadata/         # Iceberg metadata             │ │
│  │ │   └── data/             # Parquet files (partitioned)  │ │
│  │ └── task_index/           # Per-run index                │ │
│  │     ├── metadata/                                        │ │
│  │     └── data/                                            │ │
│  └─────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

## Schema

### task_cache Table

| Column | Type | Description |
|--------|------|-------------|
| hash_key | STRING | Task hash (content-addressable) |
| data | BINARY | Serialized task data (Kryo) |
| created_at | TIMESTAMP | When entry was created |
| pipeline | STRING | Pipeline name |
| run_id | STRING | Nextflow run UUID |
| ref_count | INT | Reference count for cleanup |

### task_index Table

| Column | Type | Description |
|--------|------|-------------|
| hash_key | STRING | Task hash |
| run_id | STRING | Nextflow run UUID (partition key) |
| cached | BOOLEAN | Whether task was cached vs computed |
| indexed_at | TIMESTAMP | When indexed |

## Maintenance

### Expire Old Snapshots

```bash
# Via Spark SQL (if using Spark)
CALL nextflow_cache.system.expire_snapshots('task_cache', TIMESTAMP '2024-01-01')

# Or schedule via Nextflow pipeline
```

### Compact Data Files

```bash
# Via Spark SQL
CALL nextflow_cache.system.rewrite_data_files('task_cache')
```

## Comparison with nf-cloudcache

| Feature | nf-cloudcache | nf-iceberg |
|---------|---------------|------------|
| Storage | Cloud object storage | Cloud + Iceberg format |
| Concurrency | Single writer | Multiple writers (ACID) |
| Time travel | ❌ | ✅ Snapshot-based |
| Schema evolution | ❌ | ✅ |
| Deduplication | ❌ | ✅ Content-addressable |
| Query capability | ❌ | ✅ SQL via Spark/Trino |
| Compression | Basic | Configurable (zstd, etc.) |
| Global sharing | Limited | ✅ Full support |

## Troubleshooting

### Common Issues

**"Cache table does not exist"**
- Ensure warehouse path is accessible
- Check credentials for cloud storage
- Verify namespace exists or let plugin create it

**Slow writes**
- Increase batch size for index writes
- Use faster compression (snappy vs zstd)
- Check network latency to storage

**Concurrent write conflicts**
- This should be rare due to ACID transactions
- If occurring, check catalog configuration
- Consider using REST catalog for better locking

## License

Apache License 2.0
