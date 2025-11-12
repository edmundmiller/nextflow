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

import nextflow.exception.AbortOperationException
import nextflow.util.SimpleHttpClient
import spock.lang.Specification

/**
 * Unit tests for DatasetHelper
 *
 * @author Edmund Miller
 */
class DatasetHelperTest extends Specification {

    def 'should build download URL correctly' () {
        given:
        def helper = new DatasetHelper('https://api.tower.nf', 'test-token')

        expect:
        helper.buildDownloadUrl('dataset-123', '1', 'data.csv') ==
            'https://api.tower.nf/datasets/dataset-123/v/1/n/data.csv'

        helper.buildDownloadUrl('my-dataset', '2', 'samples.tsv') ==
            'https://api.tower.nf/datasets/my-dataset/v/2/n/samples.tsv'
    }

    def 'should throw error when dataset ID is null' () {
        given:
        def helper = new DatasetHelper('https://api.tower.nf', 'test-token')

        when:
        helper.downloadDataset(null)

        then:
        thrown(IllegalArgumentException)
    }

    def 'should throw error when dataset ID is empty' () {
        given:
        def helper = new DatasetHelper('https://api.tower.nf', 'test-token')

        when:
        helper.downloadDataset('')

        then:
        thrown(IllegalArgumentException)
    }

    def 'should use default version and fileName when not specified' () {
        given:
        def mockClient = Mock(SimpleHttpClient)
        def helper = Spy(DatasetHelper, constructorArgs: ['https://api.tower.nf', 'test-token'])
        helper.httpClient = mockClient

        when:
        helper.downloadDataset('dataset-123')

        then:
        1 * mockClient.sendHttpMessage(
            'https://api.tower.nf/datasets/dataset-123/v/1/n/data.csv',
            null,
            'GET'
        )
        1 * mockClient.getResponseCode() >> 200
        1 * mockClient.getResponse() >> 'sample,value\nA,1\nB,2'
    }

    def 'should download dataset successfully' () {
        given:
        def mockClient = Mock(SimpleHttpClient)
        def helper = Spy(DatasetHelper, constructorArgs: ['https://api.tower.nf', 'test-token'])
        helper.httpClient = mockClient
        def expectedContent = 'sample,value\nA,1\nB,2\nC,3'

        when:
        def content = helper.downloadDataset('dataset-123', '2', 'samples.csv')

        then:
        1 * mockClient.sendHttpMessage(
            'https://api.tower.nf/datasets/dataset-123/v/2/n/samples.csv',
            null,
            'GET'
        )
        1 * mockClient.getResponseCode() >> 200
        1 * mockClient.getResponse() >> expectedContent
        content == expectedContent
    }

    def 'should handle 404 error appropriately' () {
        given:
        def mockClient = Mock(SimpleHttpClient)
        def helper = Spy(DatasetHelper, constructorArgs: ['https://api.tower.nf', 'test-token'])
        helper.httpClient = mockClient

        when:
        helper.downloadDataset('non-existent-dataset', '1', 'data.csv')

        then:
        1 * mockClient.sendHttpMessage(_, null, 'GET')
        1 * mockClient.getResponseCode() >> 404
        def error = thrown(AbortOperationException)
        error.message.contains('Dataset not found')
    }

    def 'should handle 403 error appropriately' () {
        given:
        def mockClient = Mock(SimpleHttpClient)
        def helper = Spy(DatasetHelper, constructorArgs: ['https://api.tower.nf', 'test-token'])
        helper.httpClient = mockClient

        when:
        helper.downloadDataset('restricted-dataset', '1', 'data.csv')

        then:
        1 * mockClient.sendHttpMessage(_, null, 'GET')
        1 * mockClient.getResponseCode() >> 403
        def error = thrown(AbortOperationException)
        error.message.contains('Access denied')
    }

    def 'should handle generic HTTP errors' () {
        given:
        def mockClient = Mock(SimpleHttpClient)
        def helper = Spy(DatasetHelper, constructorArgs: ['https://api.tower.nf', 'test-token'])
        helper.httpClient = mockClient

        when:
        helper.downloadDataset('dataset-123', '1', 'data.csv')

        then:
        1 * mockClient.sendHttpMessage(_, null, 'GET')
        1 * mockClient.getResponseCode() >> 500
        def error = thrown(AbortOperationException)
        error.message.contains('Failed to download dataset')
        error.message.contains('500')
    }

    def 'should handle IO exceptions' () {
        given:
        def mockClient = Mock(SimpleHttpClient)
        def helper = Spy(DatasetHelper, constructorArgs: ['https://api.tower.nf', 'test-token'])
        helper.httpClient = mockClient

        when:
        helper.downloadDataset('dataset-123', '1', 'data.csv')

        then:
        1 * mockClient.sendHttpMessage(_, null, 'GET') >> { throw new IOException('Network error') }
        def error = thrown(AbortOperationException)
        error.message.contains('Failed to download dataset')
        error.message.contains('Network error')
    }

}
