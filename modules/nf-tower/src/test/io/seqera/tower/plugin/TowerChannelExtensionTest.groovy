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
import spock.lang.Specification

/**
 * Unit tests for TowerChannelExtension
 *
 * @author Edmund Miller
 */
class TowerChannelExtensionTest extends Specification {

    def 'should require datasetId in map' () {
        when:
        TowerChannelExtension.fromDataset(Channel, [:])

        then:
        thrown(IllegalArgumentException)
    }

    def 'should require datasetId to be non-empty' () {
        when:
        TowerChannelExtension.fromDataset(Channel, [datasetId: ''])

        then:
        thrown(IllegalArgumentException)
    }

    def 'should handle null datasetId' () {
        when:
        TowerChannelExtension.fromDataset(Channel, [datasetId: null])

        then:
        thrown(IllegalArgumentException)
    }

    def 'should convert string parameter to map format' () {
        when:
        // This should internally convert to [datasetId: 'test-id']
        // We're just verifying it doesn't throw an exception during parameter conversion
        def result = TowerChannelExtension.fromDataset(Channel, 'test-id')

        then:
        // Since we can't easily mock DatasetHelper construction without global spies,
        // and DatasetHelper will fail without a real session/token,
        // we expect this to throw an exception about missing token (not parameter validation)
        def e = thrown(Exception)
        // Should fail on token access, not parameter validation
        e.message?.contains('token') || e.message?.contains('session')
    }

}
