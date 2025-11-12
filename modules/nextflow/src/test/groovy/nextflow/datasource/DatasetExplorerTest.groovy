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

import nextflow.exception.AbortOperationException
import spock.lang.Specification

/**
 * Test DatasetExplorer functionality
 *
 * @author Edmund Miller
 */
class DatasetExplorerTest extends Specification {

    def 'should return download url' () {
        given:
        def explorer = Spy(DatasetExplorer)
        explorer.datasetId = DATASET_ID
        explorer.endpoint = ENDPOINT
        explorer.version = VERSION

        when:
        explorer.fileName = FILENAME
        def result = explorer.getDownloadUrl()

        then:
        result == EXPECTED

        where:
        DATASET_ID  | ENDPOINT                  | VERSION | FILENAME      | EXPECTED
        'ds.123abc' | 'https://api.tower.nf'    | '1'     | 'data.csv'    | 'https://api.tower.nf/datasets/ds.123abc/v/1/n/data.csv'
        'ds.456def' | 'https://api.tower.nf'    | '2'     | 'sample.tsv'  | 'https://api.tower.nf/datasets/ds.456def/v/2/n/sample.tsv'
        'ds.789ghi' | 'https://custom.api.com'  | '1'     | 'test.csv'    | 'https://custom.api.com/datasets/ds.789ghi/v/1/n/test.csv'
    }

    def 'should return download url with encoded filename' () {
        given:
        def explorer = Spy(DatasetExplorer)
        explorer.datasetId = 'ds.123'
        explorer.endpoint = 'https://api.tower.nf'
        explorer.version = '1'
        explorer.fileName = 'my file.csv'

        when:
        def result = explorer.getDownloadUrl()

        then:
        result == 'https://api.tower.nf/datasets/ds.123/v/1/n/my+file.csv'
    }

    def 'should use default endpoint' () {
        given:
        def explorer = Spy(DatasetExplorer)

        when:
        def result = explorer.getConfigEndpoint()

        then:
        1 * explorer.getEnv() >> [:]
        result == 'https://api.tower.nf'
    }

    def 'should retrieve access token from environment' () {
        given:
        def explorer = Spy(DatasetExplorer)

        when:
        def result = explorer.getAccessToken()

        then:
        1 * explorer.getEnv() >> [TOWER_ACCESS_TOKEN: 'test_token_123']
        result == 'test_token_123'
    }

    def 'should throw error when access token is missing' () {
        given:
        def explorer = Spy(DatasetExplorer)

        when:
        explorer.getAccessToken()

        then:
        1 * explorer.getEnv() >> [:]
        thrown(AbortOperationException)
    }

    def 'should throw error when fileName is missing' () {
        given:
        def explorer = new DatasetExplorer('ds.123', [:])

        when:
        explorer.getDatasetFileName()

        then:
        thrown(AbortOperationException)
    }

    def 'should use provided fileName' () {
        given:
        def explorer = new DatasetExplorer('ds.123', [fileName: 'test.csv'])

        when:
        def result = explorer.getDatasetFileName()

        then:
        result == 'test.csv'
    }

    def 'should initialize with options' () {
        given:
        def opts = [
            endpoint: 'https://custom.api.com',
            version: '2',
            fileName: 'data.csv'
        ]

        when:
        def explorer = new DatasetExplorer('ds.123', opts)

        then:
        explorer.datasetId == 'ds.123'
        explorer.endpoint == 'https://custom.api.com'
        explorer.version == '2'
        explorer.fileName == 'data.csv'
    }

    def 'should use default version' () {
        given:
        def explorer = new DatasetExplorer('ds.123', [:])

        expect:
        explorer.version == '1'
    }
}
