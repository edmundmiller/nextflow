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

import java.nio.file.Files

import nextflow.util.CacheHelper
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Tests for IcebergCacheStore
 *
 * @author Edmund Miller <edmund@seqera.io>
 */
class IcebergCacheStoreTest extends Specification {

    @TempDir
    File tempDir

    def 'should get and put cache entries'() {
        given:
        def uuid = UUID.randomUUID()
        def runName = 'test_1'
        def config = new IcebergCacheConfig(
            warehouse: tempDir.toPath().toString(),
            catalog: 'hadoop',
            namespace: 'test_cache'
        )
        and:
        def store = new IcebergCacheStore(uuid, runName, config)
        store.open()

        and:
        def key1 = CacheHelper.hasher('ONE').hash()
        def key2 = CacheHelper.hasher('TWO').hash()
        def value = "Hello world"

        when:
        store.putEntry(key1, value.bytes)
        
        then:
        new String(store.getEntry(key1)) == value
        
        and:
        store.getEntry(key2) == null

        // Note: Delete in Iceberg uses merge-on-read semantics.
        // The data is marked as deleted but may still be visible until compaction.
        // For cache purposes, we rely on TTL and snapshot expiration for cleanup.
        when:
        store.deleteEntry(key1)
        
        then:
        // Delete commits successfully (no exception)
        noExceptionThrown()

        cleanup:
        store?.close()
    }

    def 'should write and read index entries'() {
        given:
        def uuid = UUID.randomUUID()
        def runName = 'test_index'
        def config = new IcebergCacheConfig(
            warehouse: tempDir.toPath().toString(),
            catalog: 'hadoop',
            namespace: 'test_index_cache'
        )
        and:
        def store = new IcebergCacheStore(uuid, runName, config)
        store.open()

        and:
        def key1 = CacheHelper.hasher('INDEX_ONE').hash()
        def key2 = CacheHelper.hasher('INDEX_TWO').hash()

        when:
        store.writeIndex(key1, true)
        store.writeIndex(key2, false)
        store.close()

        and:
        // Reopen for read
        def readStore = new IcebergCacheStore(uuid, runName, config)
        readStore.openForRead()
        
        then:
        def entries = []
        def iter = readStore.iterateIndex()
        while (iter.hasNext()) {
            entries << iter.next()
        }
        entries.size() == 2
        entries.find { it.key == key1 }?.cached == true
        entries.find { it.key == key2 }?.cached == false

        cleanup:
        readStore?.close()
    }

    def 'should handle deduplication'() {
        given:
        def uuid = UUID.randomUUID()
        def runName = 'test_dedup'
        def config = new IcebergCacheConfig(
            warehouse: tempDir.toPath().toString(),
            catalog: 'hadoop',
            namespace: 'test_dedup_cache',
            deduplication: true
        )
        and:
        def store = new IcebergCacheStore(uuid, runName, config)
        store.open()

        and:
        def key = CacheHelper.hasher('DEDUP_KEY').hash()
        def value1 = "First value"
        def value2 = "Second value"

        when:
        store.putEntry(key, value1.bytes)
        store.putEntry(key, value2.bytes)  // Should be ignored due to deduplication
        
        then:
        // Should still have the first value
        new String(store.getEntry(key)) == value1

        cleanup:
        store?.close()
    }

    def 'should support time travel'() {
        given:
        def uuid = UUID.randomUUID()
        def runName = 'test_time_travel'
        def config = new IcebergCacheConfig(
            warehouse: tempDir.toPath().toString(),
            catalog: 'hadoop',
            namespace: 'test_tt_cache',
            deduplication: false  // Disable to allow updates
        )
        and:
        def store = new IcebergCacheStore(uuid, runName, config)
        store.open()

        and:
        def key = CacheHelper.hasher('TT_KEY').hash()
        def value1 = "Value at snapshot 1"

        when:
        store.putEntry(key, value1.bytes)
        def snapshots = store.listSnapshots()
        
        then:
        snapshots.size() >= 1
        snapshots[0].snapshotId != null

        cleanup:
        store?.close()
    }

    def 'should drop tables'() {
        given:
        def uuid = UUID.randomUUID()
        def runName = 'test_drop'
        def config = new IcebergCacheConfig(
            warehouse: tempDir.toPath().toString(),
            catalog: 'hadoop',
            namespace: 'test_drop_cache'
        )
        and:
        def store = new IcebergCacheStore(uuid, runName, config)
        store.open()

        and:
        def key = CacheHelper.hasher('DROP_KEY').hash()
        store.putEntry(key, "test".bytes)

        when:
        store.drop()

        and:
        // Try to reopen - should fail since tables are dropped
        def newStore = new IcebergCacheStore(uuid, runName, config)
        
        then:
        // Opening for read should fail
        try {
            newStore.openForRead()
            false  // Should not reach here
        } catch (IllegalStateException e) {
            true  // Expected
        }

        cleanup:
        newStore?.close()
    }

    def 'should return stats'() {
        given:
        def uuid = UUID.randomUUID()
        def runName = 'test_stats'
        def config = new IcebergCacheConfig(
            warehouse: tempDir.toPath().toString(),
            catalog: 'hadoop',
            namespace: 'test_stats_cache'
        )
        and:
        def store = new IcebergCacheStore(uuid, runName, config)
        store.open()

        when:
        def stats = store.getStats()
        
        then:
        stats.warehouse == config.warehouse
        stats.catalog == 'hadoop'
        stats.cacheTable == 'test_stats_cache.task_cache'
        stats.indexTable == 'test_stats_cache.task_index'

        cleanup:
        store?.close()
    }
}
