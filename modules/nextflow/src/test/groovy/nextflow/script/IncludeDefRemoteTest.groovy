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

package nextflow.script

import spock.lang.Specification

/**
 * Test suite for remote module includes via IncludeDef
 *
 * Tests the enhanced include statement that supports remote module URLs:
 * include { MODULE } from "github:owner/repo/path@revision"
 *
 * @author Edmund Miller <edmund.a.miller@gmail.com>
 */
class IncludeDefRemoteTest extends Specification {

    def 'should detect remote module URLs'() {
        given:
        def includeDef = new IncludeDef()

        expect:
        includeDef.isRemoteModuleUrl('github:nf-core/modules/path@abc123')
        includeDef.isRemoteModuleUrl('gitlab:myorg/repo/path@v1.0')
        includeDef.isRemoteModuleUrl('bitbucket:company/repo/path@main')

        and:
        !includeDef.isRemoteModuleUrl('./local/path.nf')
        !includeDef.isRemoteModuleUrl('../relative/path.nf')
        !includeDef.isRemoteModuleUrl('/absolute/path.nf')
        !includeDef.isRemoteModuleUrl(null)
    }

    def 'should allow remote module URLs in checkValidPath'() {
        given:
        def includeDef = new IncludeDef()

        when:
        includeDef.checkValidPath('github:nf-core/modules/path@abc123')

        then:
        noExceptionThrown()

        when:
        includeDef.checkValidPath('gitlab:myorg/repo/path@v1.0')

        then:
        noExceptionThrown()
    }

    def 'should allow local paths in checkValidPath'() {
        given:
        def includeDef = new IncludeDef()

        when:
        includeDef.checkValidPath('./local/module.nf')

        then:
        noExceptionThrown()

        when:
        includeDef.checkValidPath('../relative/module.nf')

        then:
        noExceptionThrown()

        when:
        includeDef.checkValidPath('/absolute/path/module.nf')

        then:
        noExceptionThrown()
    }

    def 'should reject invalid paths'() {
        given:
        def includeDef = new IncludeDef()

        when:
        includeDef.checkValidPath('invalid/path')

        then:
        thrown(Exception)

        when:
        includeDef.checkValidPath(null)

        then:
        thrown(Exception)
    }

    def 'should handle integrity check security modes'() {
        given:
        def includeDef = new IncludeDef()

        when: 'strict mode should throw'
        includeDef.handleIntegrityFailure('testmodule', 'strict')

        then:
        thrown(Exception)

        when: 'warn mode should not throw'
        includeDef.handleIntegrityFailure('testmodule', 'warn')

        then:
        noExceptionThrown()

        when: 'permissive mode should not throw'
        includeDef.handleIntegrityFailure('testmodule', 'permissive')

        then:
        noExceptionThrown()
    }
}
