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

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.Channel
import nextflow.Global
import nextflow.NF
import nextflow.Session
import nextflow.dag.NodeMarker
import nextflow.extension.CH

/**
 * Channel extension methods for Tower/Seqera Platform integration
 *
 * @author Edmund Miller
 */
@Slf4j
@CompileStatic
class TowerChannelExtension {

    /**
     * Download a dataset from Seqera Platform and return its content as a String
     *
     * This function downloads a dataset file from Seqera Platform.
     * It can be used in combination with other Channel factory methods.
     *
     * Example usage:
     * <pre>
     * // Basic usage - returns the dataset content as a string
     * def dataset = Channel.fromDataset('my-dataset-id')
     *
     * // With nf-schema integration
     * ch_input = Channel.fromList(
     *     samplesheetToList(Channel.fromDataset(params.input), "assets/schema_input.json")
     * )
     *
     * // Specify version and filename
     * def dataset = Channel.fromDataset(
     *     datasetId: 'my-dataset-id',
     *     version: '2',
     *     fileName: 'samples.csv'
     * )
     * </pre>
     *
     * @param datasetId The dataset ID (when called with a string)
     * @return The content of the dataset file as a String
     */
    static String fromDataset(Channel self, String datasetId) {
        return fromDataset(self, [datasetId: datasetId])
    }

    /**
     * Download a dataset from Seqera Platform with options
     *
     * @param opts Map with options:
     *   - datasetId: (required) The dataset ID to download
     *   - version: (optional) The version of the dataset (defaults to '1')
     *   - fileName: (optional) The name of the file in the dataset (defaults to 'data.csv')
     * @return The content of the dataset file as a String
     */
    static String fromDataset(Channel self, Map opts) {
        final datasetId = opts.datasetId as String
        final version = opts.version as String
        final fileName = opts.fileName as String

        if( !datasetId )
            throw new IllegalArgumentException("fromDataset requires 'datasetId' parameter")

        // Check if Tower is configured
        checkTowerEnabled()

        log.debug "Fetching dataset: $datasetId (version: ${version ?: 'latest'}, file: ${fileName ?: 'data.csv'})"

        final helper = new DatasetHelper()
        final content = helper.downloadDataset(datasetId, version, fileName)

        log.trace "Dataset content retrieved: ${content?.length() ?: 0} characters"

        return content
    }

    /**
     * Check if Tower is properly configured
     */
    protected static void checkTowerEnabled() {
        def session = Global.session as Session
        if( !session ) {
            log.warn "Session not initialized - Tower configuration cannot be validated"
            return
        }

        // We don't require tower.enabled=true for fromDataset to work
        // as long as the access token is available
        def token = session.config?.navigate('tower.accessToken')
        if( !token ) {
            token = System.getenv('TOWER_ACCESS_TOKEN')
        }

        if( !token ) {
            log.debug "Tower access token not found - fromDataset may fail"
        }
    }

}
