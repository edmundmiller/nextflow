# nf-iceberg Agent Guidelines

## Overview

Apache Iceberg-based global cache plugin for Nextflow. Enables cache sharing across pipelines, users, and environments with ACID transactions, time travel, and cloud-native storage.

## Catalog Selection

### Tradeoffs

| Catalog | Setup Complexity | External Dependencies | Concurrent Safety | Cloud Native | Best For |
|---------|-----------------|----------------------|-------------------|--------------|----------|
| **Hadoop** | ⭐ Lowest | None (filesystem only) | ⚠️ Relies on atomic rename | ✅ S3/GCS/local | Simple setups, single-user |
| **JDBC** | ⭐⭐ Low | Any SQL DB (SQLite, Postgres, MySQL) | ✅ DB transactions | ✅ Any storage | Multi-user, self-hosted |
| **REST** | ⭐⭐⭐ Medium | REST server (Polaris, Tabular, etc.) | ✅ Server-side | ✅ Any storage | Enterprise, managed catalogs |
| **Glue** | ⭐⭐ Low | AWS account | ✅ AWS-managed | ✅ S3 only | AWS-native workloads |
| **Nessie** | ⭐⭐⭐ Medium | Nessie server | ✅ Git-like branching | ✅ Any storage | Version control for data |

### Hadoop Catalog (current default)

```
✅ Zero external dependencies - just point at S3/GCS bucket
✅ Works anywhere Nextflow runs
⚠️ Atomic rename can fail on S3 (eventually consistent in some regions)
⚠️ No locking - concurrent writes from different pipelines may conflict
```

### JDBC Catalog (recommended for multi-user)

```
✅ True ACID with database transactions
✅ SQLite for local/single-node (zero infra)
✅ Postgres/MySQL for multi-user teams
✅ Works with any object storage
⚠️ Need to provision/manage database
```

### REST Catalog (recommended for enterprise)

```
✅ Standard API - works with Polaris, Tabular, Lakekeeper
✅ Credential vending for secure multi-tenant access
✅ Server handles conflict resolution
⚠️ Requires running a catalog server
```

### Design Decision: Keep Hadoop as Default

1. Zero-config experience matters for adoption
2. Most Nextflow users run single pipelines sequentially
3. S3's atomic operations are sufficient for sequential runs
4. Users who need concurrent safety can opt-in to JDBC/REST

## Configuration Examples

```groovy
// Hadoop (default) - simplest setup
iceberg {
    warehouse = 's3://bucket/cache'
    catalog = 'hadoop'
}

// JDBC with SQLite - local multi-pipeline safety
iceberg {
    warehouse = 's3://bucket/cache'
    catalog = 'jdbc'
    jdbcUri = 'jdbc:sqlite:/shared/cache/catalog.db'
}

// JDBC with Postgres - team environments
iceberg {
    warehouse = 's3://bucket/cache'
    catalog = 'jdbc'
    jdbcUri = 'jdbc:postgresql://localhost/iceberg'
    jdbcUser = 'cache'
    jdbcPassword = System.getenv('ICEBERG_DB_PASS')
}

// REST - enterprise/managed catalogs
iceberg {
    warehouse = 's3://bucket/cache'
    catalog = 'rest'
    catalogUri = 'https://polaris.company.com/api/catalog'
}
```

## Architecture

### Key Classes

- `IcebergCacheStore` - Core `CacheStore` implementation using Iceberg tables
- `IcebergCacheConfig` - Configuration parsing from `nextflow.config`
- `IcebergCacheFactory` - Plugin factory integrating with Nextflow's cache system
- `IcebergCachePlugin` - Plugin entry point

### Schema Design

**task_cache table:**
- `hash_key` (STRING) - Task hash (content-addressable)
- `data` (BINARY) - Serialized task data
- `created_at` (TIMESTAMP) - For TTL expiration
- `pipeline`, `run_id` (STRING) - Provenance tracking
- `ref_count` (INT) - Reference counting for GC

**task_index table:**
- `hash_key`, `run_id` - Composite key
- `cached` (BOOLEAN) - Cache hit/miss
- `indexed_at` (TIMESTAMP)

### Dependencies

- Iceberg 1.7.1 (iceberg-core, iceberg-data, iceberg-parquet)
- Hadoop 3.3.6 (hadoop-common, hadoop-hdfs-client, hadoop-mapreduce-client-core)
- Parquet (via iceberg-parquet)

## Testing

```bash
# Run tests
./gradlew :plugins:nf-iceberg:test

# Tests use temp directories with HadoopCatalog
# No external services required
```

## Future Work

- [ ] JDBC catalog support (SQLite, Postgres, MySQL)
- [ ] REST catalog support
- [ ] AWS Glue catalog support
- [ ] Compaction/maintenance commands
- [ ] Cache analytics via Spark/Trino queries
