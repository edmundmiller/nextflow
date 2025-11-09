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

import nextflow.exception.AbortOperationException
import spock.lang.Specification

/**
 * Test suite for ModuleManager
 *
 * @author Edmund Miller <edmund.a.miller@gmail.com>
 */
class ModuleManagerTest extends Specification {

    Path tempDir

    def setup() {
        tempDir = Files.createTempDirectory('module-test')
    }

    def cleanup() {
        tempDir?.deleteDir()
    }

    def 'should parse module reference with provider'() {
        when:
        def ref = ModuleManager.ModuleReference.parse('github:nf-core/modules/modules/bowtie/align@abc123')

        then:
        ref.provider == 'github'
        ref.owner == 'nf-core'
        ref.repository == 'modules'
        ref.path == 'modules/bowtie/align'
        ref.revision == 'abc123'
        ref.moduleName == 'align'
        ref.project == 'nf-core/modules'
    }

    def 'should parse module reference without provider'() {
        when:
        def ref = ModuleManager.ModuleReference.parse('nf-core/modules/modules/bowtie/align@main')

        then:
        ref.provider == 'github'
        ref.owner == 'nf-core'
        ref.repository == 'modules'
        ref.path == 'modules/bowtie/align'
        ref.revision == 'main'
        ref.moduleName == 'align'
    }

    def 'should parse module reference with deep path'() {
        when:
        def ref = ModuleManager.ModuleReference.parse('gitlab:myorg/myrepo/path/to/deep/module@v1.0')

        then:
        ref.provider == 'gitlab'
        ref.owner == 'myorg'
        ref.repository == 'myrepo'
        ref.path == 'path/to/deep/module'
        ref.revision == 'v1.0'
        ref.moduleName == 'module'
    }

    def 'should fail to parse invalid module reference - no revision'() {
        when:
        ModuleManager.ModuleReference.parse('nf-core/modules/path')

        then:
        thrown(AbortOperationException)
    }

    def 'should fail to parse invalid module reference - too short'() {
        when:
        ModuleManager.ModuleReference.parse('nf-core@main')

        then:
        thrown(AbortOperationException)
    }

    def 'should get repository url for github'() {
        when:
        def ref = ModuleManager.ModuleReference.parse('github:nf-core/modules/path@main')

        then:
        ref.repositoryUrl == 'https://github.com/nf-core/modules'
    }

    def 'should get repository url for gitlab'() {
        when:
        def ref = ModuleManager.ModuleReference.parse('gitlab:myorg/myrepo/path@main')

        then:
        ref.repositoryUrl == 'https://gitlab.com/myorg/myrepo'
    }

    def 'should get repository url for bitbucket'() {
        when:
        def ref = ModuleManager.ModuleReference.parse('bitbucket:myorg/myrepo/path@main')

        then:
        ref.repositoryUrl == 'https://bitbucket.org/myorg/myrepo'
    }

    def 'should format module reference as string'() {
        when:
        def ref = ModuleManager.ModuleReference.parse('github:nf-core/modules/modules/align@abc123')

        then:
        ref.toString() == 'github:nf-core/modules/modules/align@abc123'
    }

    def 'should create module manager with default project root'() {
        when:
        def manager = new ModuleManager(tempDir)

        then:
        manager.list().isEmpty()
    }

    def 'should save and load modules configuration'() {
        given:
        def manager = new ModuleManager(tempDir)
        def modulesDir = tempDir.resolve('modules/nf-core/modules/bowtie/align')
        Files.createDirectories(modulesDir)

        when:
        def module = new ModuleManager.InstalledModule(
            name: 'align',
            source: 'github:nf-core/modules',
            path: 'modules/bowtie/align',
            revision: 'abc123',
            installedPath: modulesDir
        )
        manager.installedModules['align'] = module
        manager.saveConfig()

        and:
        def manager2 = new ModuleManager(tempDir)

        then:
        manager2.list().size() == 1
        manager2.list()[0].name == 'align'
        manager2.list()[0].source == 'github:nf-core/modules'
        manager2.list()[0].revision == 'abc123'
    }

    def 'should check if module is installed'() {
        given:
        def manager = new ModuleManager(tempDir)
        def modulesDir = tempDir.resolve('modules/test/module')
        Files.createDirectories(modulesDir)

        when:
        manager.installedModules['testmodule'] = new ModuleManager.InstalledModule(
            name: 'testmodule',
            source: 'github:test/repo',
            path: 'module',
            revision: 'v1.0',
            installedPath: modulesDir
        )

        then:
        manager.isInstalled('testmodule')
        !manager.isInstalled('notinstalled')
    }

    def 'should remove installed module'() {
        given:
        def manager = new ModuleManager(tempDir)
        def modulesDir = tempDir.resolve('modules/test/module')
        Files.createDirectories(modulesDir)
        def testFile = modulesDir.resolve('main.nf')
        testFile.text = 'process TEST { }'

        and:
        manager.installedModules['testmodule'] = new ModuleManager.InstalledModule(
            name: 'testmodule',
            source: 'github:test/repo',
            path: 'module',
            revision: 'v1.0',
            installedPath: modulesDir
        )

        when:
        manager.remove('testmodule')

        then:
        !manager.isInstalled('testmodule')
        !Files.exists(modulesDir)
        !Files.exists(testFile)
    }

    def 'should fail to remove non-existent module'() {
        given:
        def manager = new ModuleManager(tempDir)

        when:
        manager.remove('nonexistent')

        then:
        thrown(AbortOperationException)
    }

    def 'should get module info'() {
        given:
        def manager = new ModuleManager(tempDir)
        def modulesDir = tempDir.resolve('modules/test/module')
        Files.createDirectories(modulesDir)

        and:
        manager.installedModules['testmodule'] = new ModuleManager.InstalledModule(
            name: 'testmodule',
            source: 'github:test/repo',
            path: 'module',
            revision: 'v1.0',
            installedPath: modulesDir
        )

        when:
        def info = manager.info('testmodule')

        then:
        info.name == 'testmodule'
        info.source == 'github:test/repo'
        info.path == 'module'
        info.revision == 'v1.0'
    }

    def 'should fail to get info for non-existent module'() {
        given:
        def manager = new ModuleManager(tempDir)

        when:
        manager.info('nonexistent')

        then:
        thrown(AbortOperationException)
    }

    def 'should convert installed module to json'() {
        given:
        def modulesDir = tempDir.resolve('modules/test/module')
        def module = new ModuleManager.InstalledModule(
            name: 'testmodule',
            source: 'github:test/repo',
            path: 'module',
            revision: 'v1.0',
            installedPath: modulesDir
        )

        when:
        def json = module.toJson()

        then:
        json.source == 'github:test/repo'
        json.path == 'module'
        json.revision == 'v1.0'
        json.installedPath == modulesDir.toString()
    }

    def 'should create installed module from json'() {
        given:
        def modulesDir = tempDir.resolve('modules/test/module')
        def json = [
            source: 'github:test/repo',
            path: 'module',
            revision: 'v1.0',
            installedPath: modulesDir.toString()
        ]

        when:
        def module = ModuleManager.InstalledModule.fromJson('testmodule', json)

        then:
        module.name == 'testmodule'
        module.source == 'github:test/repo'
        module.path == 'module'
        module.revision == 'v1.0'
        module.installedPath == modulesDir
    }

    def 'should list installed modules'() {
        given:
        def manager = new ModuleManager(tempDir)
        def module1Dir = tempDir.resolve('modules/test/module1')
        def module2Dir = tempDir.resolve('modules/test/module2')
        Files.createDirectories(module1Dir)
        Files.createDirectories(module2Dir)

        when:
        manager.installedModules['module1'] = new ModuleManager.InstalledModule(
            name: 'module1',
            source: 'github:test/repo',
            path: 'module1',
            revision: 'v1.0',
            installedPath: module1Dir
        )
        manager.installedModules['module2'] = new ModuleManager.InstalledModule(
            name: 'module2',
            source: 'github:test/repo',
            path: 'module2',
            revision: 'v2.0',
            installedPath: module2Dir
        )

        and:
        def list = manager.list()

        then:
        list.size() == 2
        list.find { it.name == 'module1' }
        list.find { it.name == 'module2' }
    }

    def 'should handle empty modules config'() {
        given:
        def manager = new ModuleManager(tempDir)

        expect:
        manager.list().isEmpty()
    }

    def 'should handle corrupted modules.json gracefully'() {
        given:
        def configFile = tempDir.resolve('modules.json')
        configFile.text = 'invalid json {'

        when:
        new ModuleManager(tempDir)

        then:
        thrown(AbortOperationException)
    }

    def 'should copy directory recursively'() {
        given:
        def manager = new ModuleManager(tempDir)
        def sourceDir = tempDir.resolve('source')
        def targetDir = tempDir.resolve('target')
        Files.createDirectories(sourceDir)

        and:
        sourceDir.resolve('file1.txt').text = 'content1'
        def subdir = sourceDir.resolve('subdir')
        Files.createDirectories(subdir)
        subdir.resolve('file2.txt').text = 'content2'

        when:
        manager.copyDirectory(sourceDir, targetDir)

        then:
        Files.exists(targetDir.resolve('file1.txt'))
        Files.exists(targetDir.resolve('subdir/file2.txt'))
        targetDir.resolve('file1.txt').text == 'content1'
        targetDir.resolve('subdir/file2.txt').text == 'content2'
    }

    def 'should delete directory recursively'() {
        given:
        def manager = new ModuleManager(tempDir)
        def dirToDelete = tempDir.resolve('todelete')
        Files.createDirectories(dirToDelete)

        and:
        dirToDelete.resolve('file1.txt').text = 'content1'
        def subdir = dirToDelete.resolve('subdir')
        Files.createDirectories(subdir)
        subdir.resolve('file2.txt').text = 'content2'

        when:
        manager.deleteDirectory(dirToDelete)

        then:
        !Files.exists(dirToDelete)
    }
}
