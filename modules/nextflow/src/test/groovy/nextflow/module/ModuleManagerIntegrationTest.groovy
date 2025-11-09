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

package nextflow.module

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.IgnoreIf
import spock.lang.Requires
import spock.lang.Specification

/**
 * Integration test suite for ModuleManager
 *
 * These tests require network access and access to real repositories.
 * Run with: ./gradlew test --tests ModuleManagerIntegrationTest
 *
 * @author Edmund Miller <edmund.a.miller@gmail.com>
 */
@IgnoreIf({System.getenv('NXF_SMOKE')})
class ModuleManagerIntegrationTest extends Specification {

    Path tempDir

    def setup() {
        tempDir = Files.createTempDirectory('module-integration-test')
    }

    def cleanup() {
        tempDir?.deleteDir()
    }

    @Requires({ System.getenv('NXF_GITHUB_ACCESS_TOKEN') })
    def 'should install module from nf-core/modules repository'() {
        given:
        def manager = new ModuleManager(tempDir)
        def moduleRef = 'github:nf-core/modules/modules/nf-core/fastqc@master'

        when:
        def installed = manager.install(moduleRef)

        then:
        installed != null
        installed.name == 'fastqc'
        installed.source == 'github:nf-core/modules'
        installed.path == 'modules/nf-core/fastqc'
        Files.exists(installed.installedPath.resolve('main.nf'))

        and:
        def configFile = tempDir.resolve('modules.json')
        Files.exists(configFile)

        cleanup:
        manager?.remove('fastqc')
    }

    @Requires({ System.getenv('NXF_GITHUB_ACCESS_TOKEN') })
    def 'should not reinstall already installed module'() {
        given:
        def manager = new ModuleManager(tempDir)
        def moduleRef = 'github:nf-core/modules/modules/nf-core/fastqc@master'

        when:
        def installed1 = manager.install(moduleRef)
        def installed2 = manager.install(moduleRef)

        then:
        installed1.name == installed2.name
        installed1.revision == installed2.revision

        cleanup:
        manager?.remove('fastqc')
    }

    @Requires({ System.getenv('NXF_GITHUB_ACCESS_TOKEN') })
    def 'should force reinstall with force flag'() {
        given:
        def manager = new ModuleManager(tempDir)
        def moduleRef = 'github:nf-core/modules/modules/nf-core/fastqc@master'

        when:
        manager.install(moduleRef)
        def moduleFile = tempDir.resolve('modules/nf-core/modules/modules/nf-core/fastqc/main.nf')
        def originalContent = moduleFile.text
        moduleFile.text = "// Modified"

        and:
        manager.install(moduleRef, true)
        def newContent = moduleFile.text

        then:
        newContent != "// Modified"
        newContent == originalContent

        cleanup:
        manager?.remove('fastqc')
    }

    @Requires({ System.getenv('NXF_GITHUB_ACCESS_TOKEN') })
    def 'should update module to new revision'() {
        given:
        def manager = new ModuleManager(tempDir)
        def moduleRef1 = 'github:nf-core/modules/modules/nf-core/fastqc@master'

        when:
        manager.install(moduleRef1)
        def updated = manager.update('fastqc', 'master')

        then:
        updated.name == 'fastqc'
        updated.revision == 'master'

        cleanup:
        manager?.remove('fastqc')
    }

    @Requires({ System.getenv('NXF_GITHUB_ACCESS_TOKEN') })
    def 'should handle complete module lifecycle'() {
        given:
        def manager = new ModuleManager(tempDir)
        def moduleRef = 'github:nf-core/modules/modules/nf-core/fastqc@master'

        when: 'install module'
        def installed = manager.install(moduleRef)

        then:
        manager.isInstalled('fastqc')
        manager.list().size() == 1

        when: 'get module info'
        def info = manager.info('fastqc')

        then:
        info.name == 'fastqc'
        info.source == 'github:nf-core/modules'

        when: 'remove module'
        manager.remove('fastqc')

        then:
        !manager.isInstalled('fastqc')
        manager.list().isEmpty()
    }

    def 'should handle invalid repository gracefully'() {
        given:
        def manager = new ModuleManager(tempDir)
        def moduleRef = 'github:invalid/nonexistent/path@main'

        when:
        manager.install(moduleRef)

        then:
        thrown(Exception)
    }

    def 'should handle missing module file gracefully'() {
        given:
        def manager = new ModuleManager(tempDir)
        def moduleRef = 'github:nf-core/modules/nonexistent/path@master'

        when:
        manager.install(moduleRef)

        then:
        thrown(Exception)
    }
}
