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

package nextflow.datasource

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Global
import nextflow.Session
import nextflow.exception.AbortOperationException
import nextflow.util.SimpleHttpClient

/**
 * Download datasets from Seqera Platform
 *
 * @author Edmund Miller
 */
@Slf4j
@CompileStatic
class DatasetExplorer {

    static public Map PARAMS = [
            endpoint: String,
            version: String,
            fileName: String
    ]

    private String datasetId
    private String endpoint
    private String version
    private String fileName
    private String accessToken
    private JsonSlurper jsonSlurper = new JsonSlurper()

    DatasetExplorer() {
    }

    DatasetExplorer(String datasetId, Map opts) {
        this.datasetId = datasetId
        init(opts)
    }

    DatasetExplorer setDatasetId(String datasetId) {
        this.datasetId = datasetId
        return this
    }

    protected void init(Map opts) {
        this.endpoint = opts.endpoint as String ?: getConfigEndpoint()
        this.version = opts.version as String ?: '1'
        this.fileName = opts.fileName as String
    }

    protected Map getEnv() {
        System.getenv()
    }

    protected String getConfigEndpoint() {
        def session = Global.session as Session
        def result = session?.config?.navigate('tower.endpoint')
        if (!result)
            result = 'https://api.tower.nf'
        return result as String
    }

    protected String getAccessToken() {
        def session = Global.session as Session
        def token = session?.config?.navigate('tower.accessToken')
        if (!token)
            token = getEnv().get('TOWER_ACCESS_TOKEN')
        if (!token)
            throw new AbortOperationException("Missing Seqera Platform access token -- Make sure there's a variable TOWER_ACCESS_TOKEN in your environment or tower.accessToken in your config")
        return token as String
    }

    /**
     * Fetch dataset metadata to determine the fileName if not provided
     * TODO: Implement this when the list-datasets API is available
     */
    protected String getDatasetFileName() {
        if (fileName)
            return fileName

        // TODO: In the future, we can query the dataset metadata to get the fileName
        // For now, we'll use a default pattern or require the user to provide it
        throw new AbortOperationException("fileName parameter is required for fromDataset(). Future versions will support automatic detection.")
    }

    protected String getDownloadUrl() {
        final name = getDatasetFileName()
        return "${endpoint}/datasets/${datasetId}/v/${version}/n/${URLEncoder.encode(name, "UTF-8")}"
    }

    /**
     * Download the dataset and return its content
     */
    String apply() {
        if (!accessToken)
            accessToken = getAccessToken()

        final url = getDownloadUrl()
        log.debug "Fetching dataset from: $url"

        try {
            final client = new SimpleHttpClient()
            client.setAuthToken("Bearer ${accessToken}")

            // Make HTTP GET request
            final connection = new URL(url).openConnection() as HttpURLConnection
            connection.setRequestMethod('GET')
            connection.setRequestProperty('Authorization', "Bearer ${accessToken}")
            connection.setRequestProperty('Accept', 'text/csv, text/plain, */*')

            final responseCode = connection.responseCode

            if (responseCode == 200) {
                final content = connection.inputStream.text
                log.trace "Dataset content received:\n${content?.take(500)}"
                return content
            }
            else if (responseCode == 403) {
                throw new AbortOperationException("Access forbidden to dataset ${datasetId} -- Check your permissions and access token")
            }
            else if (responseCode == 404) {
                throw new AbortOperationException("Dataset ${datasetId} not found -- Check the dataset ID, version, and fileName")
            }
            else {
                final errorMsg = connection.errorStream?.text ?: "HTTP ${responseCode}"
                throw new AbortOperationException("Failed to download dataset ${datasetId}: ${errorMsg}")
            }
        }
        catch (AbortOperationException e) {
            throw e
        }
        catch (Exception e) {
            throw new AbortOperationException("Error downloading dataset ${datasetId}: ${e.message}", e)
        }
    }
}
