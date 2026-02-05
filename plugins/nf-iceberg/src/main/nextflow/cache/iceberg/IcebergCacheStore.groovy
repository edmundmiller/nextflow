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

import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

import com.google.common.hash.HashCode
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.cache.CacheStore
import nextflow.util.CacheHelper
import org.apache.hadoop.conf.Configuration
import org.apache.iceberg.DataFile
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.Table
import org.apache.iceberg.catalog.Catalog
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.catalog.SupportsNamespaces
import org.apache.iceberg.catalog.TableIdentifier
import org.apache.iceberg.data.GenericRecord
import org.apache.iceberg.data.IcebergGenerics
import org.apache.iceberg.data.Record
import org.apache.iceberg.data.parquet.GenericParquetWriter
import org.apache.iceberg.expressions.Expressions
import org.apache.iceberg.hadoop.HadoopCatalog
import org.apache.iceberg.io.CloseableIterable
import org.apache.iceberg.io.DataWriter
import org.apache.iceberg.io.OutputFile
import org.apache.iceberg.parquet.Parquet
import org.apache.iceberg.types.Types

/**
 * Iceberg-based implementation of the Nextflow cache store.
 *
 * Uses Apache Iceberg for:
 * - ACID transactions (safe concurrent writes from multiple pipelines)
 * - Time travel (cache history and recovery)
 * - Efficient cloud storage (S3, GCS, Azure)
 * - Content-addressable deduplication
 *
 * @author Edmund Miller <edmund@seqera.io>
 */
@Slf4j
@CompileStatic
class IcebergCacheStore implements CacheStore {

    /** Schema for task cache entries */
    static final Schema CACHE_SCHEMA = new Schema(
        Types.NestedField.required(1, "hash_key", Types.StringType.get()),
        Types.NestedField.required(2, "data", Types.BinaryType.get()),
        Types.NestedField.required(3, "created_at", Types.TimestampType.withZone()),
        Types.NestedField.optional(4, "pipeline", Types.StringType.get()),
        Types.NestedField.optional(5, "run_id", Types.StringType.get()),
        Types.NestedField.optional(6, "ref_count", Types.IntegerType.get())
    )

    /** Schema for task index */
    static final Schema INDEX_SCHEMA = new Schema(
        Types.NestedField.required(1, "hash_key", Types.StringType.get()),
        Types.NestedField.required(2, "run_id", Types.StringType.get()),
        Types.NestedField.required(3, "cached", Types.BooleanType.get()),
        Types.NestedField.required(4, "indexed_at", Types.TimestampType.withZone())
    )

    private final int KEY_SIZE

    /** Session UUID */
    private final UUID uniqueId

    /** Run name */
    private final String runName

    /** Configuration */
    private final IcebergCacheConfig config

    /** Iceberg catalog */
    private Catalog catalog

    /** Cache table */
    private Table cacheTable

    /** Index table */
    private Table indexTable

    /** Hadoop configuration */
    private Configuration hadoopConf

    /** In-memory index buffer for batch writes */
    private final List<Index> indexBuffer = Collections.synchronizedList(new ArrayList<Index>())

    /** Write mode flag */
    private boolean writeMode = false

    IcebergCacheStore(UUID uniqueId, String runName, IcebergCacheConfig config) {
        assert uniqueId, "Missing 'uniqueId' argument"
        assert runName, "Missing 'runName' argument"
        assert config, "Missing 'config' argument"
        assert config.warehouse, "Missing 'warehouse' in config"

        this.KEY_SIZE = CacheHelper.hasher('x').hash().asBytes().size()
        this.uniqueId = uniqueId
        this.runName = runName
        this.config = config
    }

    @Override
    IcebergCacheStore open() {
        log.debug "Opening Iceberg cache store in write mode: warehouse=${config.warehouse}"
        this.writeMode = true
        initializeCatalog()
        ensureTablesExist()
        return this
    }

    @Override
    IcebergCacheStore openForRead() {
        log.debug "Opening Iceberg cache store in read mode: warehouse=${config.warehouse}"
        this.writeMode = false
        initializeCatalog()
        loadTables()
        return this
    }

    private void initializeCatalog() {
        hadoopConf = new Configuration()
        
        // Configure based on warehouse location
        if (config.warehouse.startsWith('s3://')) {
            hadoopConf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
            hadoopConf.set("fs.s3a.aws.credentials.provider", 
                "com.amazonaws.auth.DefaultAWSCredentialsProviderChain")
        } else if (config.warehouse.startsWith('gs://')) {
            hadoopConf.set("fs.gs.impl", "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem")
        }

        // Apply custom catalog properties
        config.catalogProperties.each { key, value ->
            hadoopConf.set(key, value)
        }

        // Use simple constructor for HadoopCatalog (recommended approach)
        catalog = new HadoopCatalog(hadoopConf, config.warehouse)

        log.debug "Initialized hadoop catalog at ${config.warehouse}"
    }

    private void ensureTablesExist() {
        def namespace = Namespace.of(config.namespace)
        
        // Create namespace if catalog supports it
        if (catalog instanceof SupportsNamespaces) {
            def nsCatalog = (SupportsNamespaces) catalog
            if (!nsCatalog.namespaceExists(namespace)) {
                nsCatalog.createNamespace(namespace)
                log.debug "Created namespace: ${config.namespace}"
            }
        }

        // Create or load cache table (unpartitioned for simplicity)
        def cacheTableId = TableIdentifier.of(namespace, config.tableName)
        if (!catalog.tableExists(cacheTableId)) {
            cacheTable = catalog.buildTable(cacheTableId, CACHE_SCHEMA)
                .withPartitionSpec(PartitionSpec.unpartitioned())
                .withProperty("format-version", "2")
                .withProperty("write.format.default", "parquet")
                .withProperty("write.parquet.compression-codec", 
                    config.compression == 'none' ? 'uncompressed' : config.compression)
                .create()
            log.info "Created Iceberg cache table: ${config.fullTableName}"
        } else {
            cacheTable = catalog.loadTable(cacheTableId)
            log.debug "Loaded existing cache table: ${config.fullTableName}"
        }

        // Create or load index table (unpartitioned)
        def indexTableId = TableIdentifier.of(namespace, config.indexTableName)
        if (!catalog.tableExists(indexTableId)) {
            indexTable = catalog.buildTable(indexTableId, INDEX_SCHEMA)
                .withPartitionSpec(PartitionSpec.unpartitioned())
                .withProperty("format-version", "2")
                .withProperty("write.parquet.compression-codec", "zstd")
                .create()
            log.info "Created Iceberg index table: ${config.fullIndexTableName}"
        } else {
            indexTable = catalog.loadTable(indexTableId)
            log.debug "Loaded existing index table: ${config.fullIndexTableName}"
        }
    }

    private void loadTables() {
        def namespace = Namespace.of(config.namespace)
        
        def cacheTableId = TableIdentifier.of(namespace, config.tableName)
        if (!catalog.tableExists(cacheTableId)) {
            throw new IllegalStateException("Cache table does not exist: ${config.fullTableName}")
        }
        cacheTable = catalog.loadTable(cacheTableId)

        def indexTableId = TableIdentifier.of(namespace, config.indexTableName)
        if (!catalog.tableExists(indexTableId)) {
            throw new IllegalStateException("Index table does not exist: ${config.fullIndexTableName}")
        }
        indexTable = catalog.loadTable(indexTableId)
    }

    @Override
    void close() {
        log.debug "Closing Iceberg cache store"
        
        // Flush any buffered index entries
        if (writeMode && !indexBuffer.isEmpty()) {
            flushIndexBuffer()
        }
    }

    @Override
    void drop() {
        log.info "Dropping Iceberg cache tables"
        def namespace = Namespace.of(config.namespace)
        
        try {
            catalog.dropTable(TableIdentifier.of(namespace, config.tableName), true)
        } catch (Exception e) {
            log.warn "Failed to drop cache table: ${e.message}"
        }
        
        try {
            catalog.dropTable(TableIdentifier.of(namespace, config.indexTableName), true)
        } catch (Exception e) {
            log.warn "Failed to drop index table: ${e.message}"
        }
    }

    @Override
    byte[] getEntry(HashCode key) {
        def hashKey = key.toString()
        log.trace "Getting cache entry: ${hashKey}"

        try {
            // Refresh table to see latest commits
            cacheTable.refresh()
            
            CloseableIterable<Record> results = IcebergGenerics.read(cacheTable)
                .where(Expressions.equal("hash_key", hashKey))
                .build()

            try {
                for (Record record : results) {
                    def dataField = record.getField("data")
                    if (dataField instanceof ByteBuffer) {
                        ByteBuffer buffer = (ByteBuffer) dataField
                        byte[] data = new byte[buffer.remaining()]
                        buffer.get(data)
                        log.trace "Found cache entry: ${hashKey} (${data.length} bytes)"
                        return data
                    } else if (dataField instanceof byte[]) {
                        log.trace "Found cache entry: ${hashKey} (${((byte[])dataField).length} bytes)"
                        return (byte[]) dataField
                    }
                }
            } finally {
                results.close()
            }
        } catch (Exception e) {
            log.warn "Failed to get cache entry ${hashKey}: ${e.message}", e
        }

        log.trace "Cache entry not found: ${hashKey}"
        return null
    }

    @Override
    void putEntry(HashCode key, byte[] value) {
        def hashKey = key.toString()
        log.trace "Putting cache entry: ${hashKey} (${value.length} bytes)"

        try {
            // Check if entry already exists (content-addressable deduplication)
            if (config.deduplication && getEntry(key) != null) {
                log.trace "Entry already exists, skipping: ${hashKey}"
                return
            }

            // Create record
            GenericRecord record = GenericRecord.create(CACHE_SCHEMA)
            record.setField("hash_key", hashKey)
            record.setField("data", ByteBuffer.wrap(value))
            record.setField("created_at", OffsetDateTime.now(ZoneOffset.UTC))
            record.setField("pipeline", System.getProperty("nextflow.pipeline.name", "unknown"))
            record.setField("run_id", uniqueId.toString())
            record.setField("ref_count", 1)

            // Write single record
            writeRecordToTable(cacheTable, CACHE_SCHEMA, record)
            log.trace "Successfully wrote cache entry: ${hashKey}"
            
        } catch (Exception e) {
            log.error "Failed to put cache entry ${hashKey}: ${e.message}", e
            throw e
        }
    }

    @Override
    void deleteEntry(HashCode key) {
        def hashKey = key.toString()
        log.trace "Deleting cache entry: ${hashKey}"

        try {
            cacheTable.newDelete()
                .deleteFromRowFilter(Expressions.equal("hash_key", hashKey))
                .commit()
        } catch (Exception e) {
            log.warn "Failed to delete cache entry ${hashKey}: ${e.message}"
        }
    }

    @Override
    void writeIndex(HashCode key, boolean cached) {
        log.trace "Writing index entry: ${key} cached=${cached}"
        indexBuffer.add(new Index(key, cached))
        
        // Flush periodically
        if (indexBuffer.size() >= 1000) {
            flushIndexBuffer()
        }
    }

    private synchronized void flushIndexBuffer() {
        if (indexBuffer.isEmpty()) return
        
        log.debug "Flushing ${indexBuffer.size()} index entries"
        
        try {
            def records = indexBuffer.collect { idx ->
                GenericRecord record = GenericRecord.create(INDEX_SCHEMA)
                record.setField("hash_key", idx.key.toString())
                record.setField("run_id", uniqueId.toString())
                record.setField("cached", idx.cached)
                record.setField("indexed_at", OffsetDateTime.now(ZoneOffset.UTC))
                return record
            }
            
            writeRecordsToTable(indexTable, INDEX_SCHEMA, records)
            indexBuffer.clear()
            
        } catch (Exception e) {
            log.error "Failed to flush index buffer: ${e.message}", e
        }
    }

    @Override
    void deleteIndex() {
        log.debug "Deleting index for run: ${uniqueId}"
        
        try {
            indexTable.newDelete()
                .deleteFromRowFilter(Expressions.equal("run_id", uniqueId.toString()))
                .commit()
        } catch (Exception e) {
            log.warn "Failed to delete index: ${e.message}"
        }
    }

    @Override
    Iterator<Index> iterateIndex() {
        log.debug "Iterating index for run: ${uniqueId}"
        
        try {
            // Refresh to see latest data
            indexTable.refresh()
            
            CloseableIterable<Record> results = IcebergGenerics.read(indexTable)
                .where(Expressions.equal("run_id", uniqueId.toString()))
                .build()

            return new Iterator<Index>() {
                private final java.util.Iterator<Record> delegate = results.iterator()
                
                @Override
                boolean hasNext() {
                    def hasMore = delegate.hasNext()
                    if (!hasMore) {
                        try { results.close() } catch (Exception ignored) {}
                    }
                    return hasMore
                }
                
                @Override
                Index next() {
                    Record record = delegate.next()
                    def hashKey = HashCode.fromString(record.getField("hash_key") as String)
                    def cached = record.getField("cached") as boolean
                    return new Index(hashKey, cached)
                }
            }
        } catch (Exception e) {
            log.error "Failed to iterate index: ${e.message}", e
            return Collections.emptyIterator()
        }
    }

    /**
     * Write a single record to a table
     */
    private void writeRecordToTable(Table table, Schema schema, GenericRecord record) {
        def outputPath = "${table.location()}/data/${UUID.randomUUID()}.parquet"
        OutputFile outputFile = table.io().newOutputFile(outputPath)
        
        log.trace "Writing to: ${outputPath}"

        DataWriter<Record> writer = Parquet.writeData(outputFile)
            .schema(schema)
            .createWriterFunc(GenericParquetWriter::buildWriter)
            .overwrite()
            .withSpec(PartitionSpec.unpartitioned())
            .build()
        
        try {
            writer.write(record)
        } finally {
            writer.close()
        }
        
        DataFile dataFile = writer.toDataFile()
        log.trace "Created data file: ${dataFile.path()} with ${dataFile.recordCount()} records"
        
        table.newAppend()
            .appendFile(dataFile)
            .commit()
        
        log.trace "Committed append to table"
    }

    /**
     * Write multiple records to a table in batch
     */
    private void writeRecordsToTable(Table table, Schema schema, List<GenericRecord> records) {
        if (records.isEmpty()) return
        
        def outputPath = "${table.location()}/data/${UUID.randomUUID()}.parquet"
        OutputFile outputFile = table.io().newOutputFile(outputPath)
        
        DataWriter<Record> writer = Parquet.writeData(outputFile)
            .schema(schema)
            .createWriterFunc(GenericParquetWriter::buildWriter)
            .overwrite()
            .withSpec(PartitionSpec.unpartitioned())
            .build()
        
        try {
            records.each { writer.write(it) }
        } finally {
            writer.close()
        }
        
        DataFile dataFile = writer.toDataFile()
        table.newAppend()
            .appendFile(dataFile)
            .commit()
    }

    /**
     * Get cache statistics
     */
    Map<String, Object> getStats() {
        Map<String, Object> result = new HashMap<>()
        result.put("warehouse", config.warehouse)
        result.put("catalog", config.catalog)
        result.put("cacheTable", config.fullTableName)
        result.put("indexTable", config.fullIndexTableName)
        result.put("cacheSnapshots", cacheTable?.currentSnapshot()?.snapshotId())
        result.put("indexSnapshots", indexTable?.currentSnapshot()?.snapshotId())
        return result
    }

    /**
     * Time travel: read cache at a specific snapshot
     */
    byte[] getEntryAtSnapshot(HashCode key, long snapshotId) {
        def hashKey = key.toString()
        
        try {
            CloseableIterable<Record> results = IcebergGenerics.read(cacheTable)
                .useSnapshot(snapshotId)
                .where(Expressions.equal("hash_key", hashKey))
                .build()

            try {
                for (Record record : results) {
                    def dataField = record.getField("data")
                    if (dataField instanceof ByteBuffer) {
                        ByteBuffer buffer = (ByteBuffer) dataField
                        byte[] data = new byte[buffer.remaining()]
                        buffer.get(data)
                        return data
                    } else if (dataField instanceof byte[]) {
                        return (byte[]) dataField
                    }
                }
            } finally {
                results.close()
            }
        } catch (Exception e) {
            log.warn "Failed to get cache entry at snapshot ${snapshotId}: ${e.message}"
        }
        
        return null
    }

    /**
     * Expire old cache entries based on TTL
     */
    void expireOldEntries() {
        if (config.ttlDays <= 0) return
        
        def cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(config.ttlDays, ChronoUnit.DAYS)
        log.info "Expiring cache entries older than ${cutoff}"
        
        try {
            cacheTable.newDelete()
                .deleteFromRowFilter(Expressions.lessThan("created_at", cutoff.toString()))
                .commit()
        } catch (Exception e) {
            log.error "Failed to expire old entries: ${e.message}", e
        }
    }

    /**
     * Expire old snapshots to reclaim storage
     */
    void expireSnapshots(int retainCount = 5) {
        log.info "Expiring old snapshots, retaining last ${retainCount}"
        
        try {
            def expireTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L) // 7 days
            cacheTable.expireSnapshots()
                .expireOlderThan(expireTime)
                .retainLast(retainCount)
                .commit()
                
            indexTable.expireSnapshots()
                .expireOlderThan(expireTime)
                .retainLast(retainCount)
                .commit()
        } catch (Exception e) {
            log.warn "Failed to expire snapshots: ${e.message}"
        }
    }

    /**
     * List available snapshots for time travel
     */
    List<Map<String, Object>> listSnapshots() {
        List<Map<String, Object>> snapshots = new ArrayList<>()
        cacheTable.snapshots().each { snapshot ->
            Map<String, Object> entry = new HashMap<>()
            entry.put("snapshotId", snapshot.snapshotId())
            entry.put("timestamp", snapshot.timestampMillis())
            entry.put("operation", snapshot.operation())
            entry.put("summary", snapshot.summary())
            snapshots.add(entry)
        }
        return snapshots
    }
}
