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

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Global
import nextflow.Session
import nextflow.cache.CacheDB
import nextflow.cache.CacheFactory
import nextflow.exception.AbortOperationException
import nextflow.plugin.Priority

/**
 * Factory for creating Iceberg-based cache instances.
 *
 * Configuration via nextflow.config:
 * {@code
 * iceberg {
 *     warehouse = 's3://my-bucket/nextflow-cache'
 *     catalog = 'hadoop'  // or 'glue', 'rest'
 *     namespace = 'nextflow_cache'
 *     globalScope = true
 *     ttlDays = 30
 * }
 * }
 *
 * Or via environment variable:
 * NXF_ICEBERG_WAREHOUSE=s3://my-bucket/nextflow-cache
 *
 * @author Edmund Miller <edmund@seqera.io>
 */
@Slf4j
@CompileStatic
@Priority(-20)  // Higher priority than CloudCacheFactory (-10)
class IcebergCacheFactory extends CacheFactory {

    static final String ENV_ICEBERG_WAREHOUSE = 'NXF_ICEBERG_WAREHOUSE'
    static final String ENV_ICEBERG_CATALOG = 'NXF_ICEBERG_CATALOG'
    static final String ENV_ICEBERG_NAMESPACE = 'NXF_ICEBERG_NAMESPACE'

    @Override
    protected CacheDB newInstance(UUID uniqueId, String runName, Path home) {
        if (!uniqueId) 
            throw new AbortOperationException("Missing cache `uuid`")
        if (!runName) 
            throw new AbortOperationException("Missing cache `runName`")

        def config = getConfig()
        if (!config) {
            throw new AbortOperationException(
                "Iceberg cache not configured - set 'iceberg.warehouse' in config or NXF_ICEBERG_WAREHOUSE env var")
        }

        log.debug "Creating Iceberg cache: warehouse=${config.warehouse}, catalog=${config.catalog}"
        
        def store = new IcebergCacheStore(uniqueId, runName, config)
        return new CacheDB(store)
    }

    /**
     * Get Iceberg configuration from session or environment
     */
    private IcebergCacheConfig getConfig() {
        def session = Global.session as Session
        def configMap = session?.config?.navigate('iceberg') as Map
        
        // Try environment variables if no config
        def warehouse = configMap?.warehouse ?: System.getenv(ENV_ICEBERG_WAREHOUSE)
        if (!warehouse) {
            return null
        }

        def config = new IcebergCacheConfig()
        config.warehouse = warehouse
        
        // Apply config map if present
        if (configMap) {
            if (configMap.catalog) config.catalog = configMap.catalog as String
            if (configMap.namespace) config.namespace = configMap.namespace as String
            if (configMap.tableName) config.tableName = configMap.tableName as String
            if (configMap.indexTableName) config.indexTableName = configMap.indexTableName as String
            if (configMap.catalogProperties) config.catalogProperties = configMap.catalogProperties as Map<String, String>
            if (configMap.globalScope != null) config.globalScope = configMap.globalScope as boolean
            if (configMap.ttlDays != null) config.ttlDays = configMap.ttlDays as int
            if (configMap.deduplication != null) config.deduplication = configMap.deduplication as boolean
            if (configMap.compression) config.compression = configMap.compression as String
            if (configMap.maxRetries != null) config.maxRetries = configMap.maxRetries as int
            if (configMap.snapshotRetention != null) config.snapshotRetention = configMap.snapshotRetention as int
        }

        // Apply environment variable overrides
        def envCatalog = System.getenv(ENV_ICEBERG_CATALOG)
        if (envCatalog) config.catalog = envCatalog

        def envNamespace = System.getenv(ENV_ICEBERG_NAMESPACE)
        if (envNamespace) config.namespace = envNamespace

        log.debug "Iceberg cache config: ${config}"
        return config
    }
}
