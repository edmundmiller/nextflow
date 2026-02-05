/*
 * Copyright 2013-2024, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.cache.iceberg

import groovy.transform.CompileStatic
import groovy.transform.ToString

/**
 * Configuration for the Iceberg-based global cache.
 *
 * Example nextflow.config:
 * {@code
 * iceberg {
 *     warehouse = 's3://my-bucket/nextflow-cache'
 *     catalog = 'hadoop'  // or 'glue', 'rest', 'jdbc'
 *     namespace = 'nextflow_cache'
 *     
 *     // Optional: catalog-specific settings
 *     catalogProperties = [
 *         'io-impl': 'org.apache.iceberg.aws.s3.S3FileIO'
 *     ]
 *     
 *     // Cache behavior
 *     globalScope = true           // Share cache across all runs
 *     ttlDays = 30                 // Expire entries after 30 days
 *     deduplication = true         // Content-addressable storage
 *     compression = 'zstd'         // Compression codec
 * }
 * }
 *
 * @author Edmund Miller <edmund@seqera.io>
 */
@CompileStatic
@ToString(includeNames = true, includePackage = false)
class IcebergCacheConfig {

    /** Catalog type: 'hadoop', 'glue', 'rest', 'jdbc', 'hive' */
    String catalog = 'hadoop'

    /** Warehouse location (S3, GCS, HDFS, or local path) */
    String warehouse

    /** Iceberg namespace for cache tables */
    String namespace = 'nextflow_cache'

    /** Table name for cache entries */
    String tableName = 'task_cache'

    /** Index table name */
    String indexTableName = 'task_index'

    /** Additional catalog properties */
    Map<String, String> catalogProperties = [:]

    /** Enable global cache scope (vs per-session) */
    boolean globalScope = true

    /** Time-to-live for cache entries in days (0 = infinite) */
    int ttlDays = 0

    /** Enable content-addressable deduplication */
    boolean deduplication = true

    /** Compression codec: 'zstd', 'snappy', 'gzip', 'none' */
    String compression = 'zstd'

    /** Number of retries for transient failures */
    int maxRetries = 3

    /** Snapshot retention count for time travel */
    int snapshotRetention = 5

    /** Enable write-ahead log for durability */
    boolean walEnabled = true

    IcebergCacheConfig() {}

    IcebergCacheConfig(Map config) {
        if (config.catalog) this.catalog = config.catalog as String
        if (config.warehouse) this.warehouse = config.warehouse as String
        if (config.namespace) this.namespace = config.namespace as String
        if (config.tableName) this.tableName = config.tableName as String
        if (config.indexTableName) this.indexTableName = config.indexTableName as String
        if (config.catalogProperties) this.catalogProperties = config.catalogProperties as Map<String, String>
        if (config.globalScope != null) this.globalScope = config.globalScope as boolean
        if (config.ttlDays != null) this.ttlDays = config.ttlDays as int
        if (config.deduplication != null) this.deduplication = config.deduplication as boolean
        if (config.compression) this.compression = config.compression as String
        if (config.maxRetries != null) this.maxRetries = config.maxRetries as int
        if (config.snapshotRetention != null) this.snapshotRetention = config.snapshotRetention as int
        if (config.walEnabled != null) this.walEnabled = config.walEnabled as boolean
    }

    /**
     * Get the full table identifier
     */
    String getFullTableName() {
        return "${namespace}.${tableName}"
    }

    /**
     * Get the full index table identifier
     */
    String getFullIndexTableName() {
        return "${namespace}.${indexTableName}"
    }
}
