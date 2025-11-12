/*
 * Copyright (c) 2019, Seqera Labs.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.tower.plugin

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Global
import nextflow.Session
import nextflow.exception.AbortOperationException
import nextflow.util.SimpleHttpClient

/**
 * Helper class to download datasets from Seqera Platform
 *
 * @author Edmund Miller
 */
@Slf4j
@CompileStatic
class DatasetHelper {

    static private final String TOKEN_PREFIX = '@token:'

    private String endpoint
    private String accessToken
    private SimpleHttpClient httpClient

    DatasetHelper() {
        this.endpoint = getEndpoint()
        this.accessToken = getAccessToken()
        this.httpClient = new SimpleHttpClient().setAuthToken(TOKEN_PREFIX + accessToken)
    }

    DatasetHelper(String endpoint, String accessToken) {
        this.endpoint = endpoint
        this.accessToken = accessToken
        this.httpClient = new SimpleHttpClient().setAuthToken(TOKEN_PREFIX + accessToken)
    }

    /**
     * Get the Tower endpoint URL from config or environment
     */
    protected String getEndpoint() {
        def session = Global.session as Session
        def endpoint = session?.config?.navigate('tower.endpoint') as String
        if( !endpoint || endpoint == '-' )
            endpoint = TowerClient.DEF_ENDPOINT_URL
        return endpoint
    }

    /**
     * Get the Tower access token from config or environment
     */
    protected String getAccessToken() {
        def session = Global.session as Session
        def token = session?.config?.navigate('tower.accessToken')
        if( !token ) {
            def env = System.getenv()
            token = env.get('TOWER_ACCESS_TOKEN')
        }
        if( !token )
            throw new AbortOperationException("Missing Nextflow Tower access token -- Make sure there's a variable TOWER_ACCESS_TOKEN in your environment")
        return token
    }

    /**
     * Download a dataset from Seqera Platform
     *
     * @param datasetId The dataset ID to download
     * @param version The version of the dataset (defaults to latest)
     * @param fileName The name of the file in the dataset (defaults to 'data.csv')
     * @return The content of the dataset file as a String
     */
    String downloadDataset(String datasetId, String version = null, String fileName = null) {
        if( !datasetId )
            throw new IllegalArgumentException("Dataset ID cannot be null or empty")

        // TODO: When version is not specified, we should query the latest version
        // For now, default to version 1 if not specified
        final versionStr = version ?: '1'

        // TODO: In the future, we should query the dataset metadata to get the actual filename
        // For now, default to 'data.csv' if not specified
        final fileNameStr = fileName ?: 'data.csv'

        final url = buildDownloadUrl(datasetId, versionStr, fileNameStr)
        log.debug "Downloading dataset from: $url"

        try {
            httpClient.sendHttpMessage(url, null, 'GET')
            final responseCode = httpClient.responseCode

            if( responseCode >= 200 && responseCode < 300 ) {
                return httpClient.getResponse()
            } else if( responseCode == 404 ) {
                throw new AbortOperationException("Dataset not found: $datasetId (version: $versionStr, file: $fileNameStr)")
            } else if( responseCode == 403 ) {
                throw new AbortOperationException("Access denied to dataset: $datasetId -- Check your Tower access token permissions")
            } else {
                throw new AbortOperationException("Failed to download dataset: $datasetId -- HTTP status: $responseCode")
            }
        } catch( IOException e ) {
            throw new AbortOperationException("Failed to download dataset: $datasetId -- ${e.message}", e)
        }
    }

    /**
     * Build the download URL for a dataset
     *
     * @param datasetId The dataset ID
     * @param version The dataset version
     * @param fileName The file name
     * @return The complete download URL
     */
    protected String buildDownloadUrl(String datasetId, String version, String fileName) {
        return "${endpoint}/datasets/${datasetId}/v/${version}/n/${fileName}"
    }

    /**
     * TODO: List all datasets in a workspace
     * This will use the /datasets API endpoint
     *
     * @param workspaceId Optional workspace ID
     * @return List of available datasets
     */
    // Future implementation for listing datasets
    // List<Map> listDatasets(String workspaceId = null) {
    //     final url = workspaceId
    //         ? "${endpoint}/datasets?workspaceId=${workspaceId}"
    //         : "${endpoint}/datasets"
    //
    //     httpClient.sendHttpMessage(url, null, 'GET')
    //     if( httpClient.responseCode >= 200 && httpClient.responseCode < 300 ) {
    //         def json = new JsonSlurper().parseText(httpClient.response)
    //         return json.datasets as List<Map>
    //     }
    //     return []
    // }

}
