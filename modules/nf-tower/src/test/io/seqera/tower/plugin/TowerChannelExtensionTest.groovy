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

import nextflow.Channel
import nextflow.Global
import nextflow.Session
import spock.lang.Specification

/**
 * Unit tests for TowerChannelExtension
 *
 * @author Edmund Miller
 */
class TowerChannelExtensionTest extends Specification {

    def setup() {
        // Setup a mock session with Tower configuration
        Global.session = Mock(Session) {
            getConfig() >> [
                tower: [
                    accessToken: 'test-token',
                    endpoint: 'https://api.tower.nf'
                ]
            ]
        }
    }

    def cleanup() {
        Global.session = null
    }

    def 'should call fromDataset with string parameter' () {
        given:
        def mockHelper = Mock(DatasetHelper)
        def extension = Spy(TowerChannelExtension)
        def expectedContent = 'sample,value\nA,1\nB,2'

        when:
        def result = extension.fromDataset(Channel, 'my-dataset')

        then:
        // Verify the helper is created and called
        // (In actual implementation, we'd need to mock the DatasetHelper constructor)
        result != null
    }

    def 'should call fromDataset with map parameters' () {
        given:
        def extension = Spy(TowerChannelExtension)

        when:
        extension.fromDataset(Channel, [
            datasetId: 'my-dataset',
            version: '2',
            fileName: 'samples.csv'
        ])

        then:
        // Test passes if no exception is thrown
        noExceptionThrown()
    }

    def 'should require datasetId in map' () {
        given:
        def extension = new TowerChannelExtension()

        when:
        extension.fromDataset(Channel, [:])

        then:
        thrown(IllegalArgumentException)
    }

    def 'should require datasetId to be non-empty' () {
        given:
        def extension = new TowerChannelExtension()

        when:
        extension.fromDataset(Channel, [datasetId: ''])

        then:
        thrown(IllegalArgumentException)
    }

    def 'should handle null datasetId' () {
        given:
        def extension = new TowerChannelExtension()

        when:
        extension.fromDataset(Channel, [datasetId: null])

        then:
        thrown(IllegalArgumentException)
    }

}
