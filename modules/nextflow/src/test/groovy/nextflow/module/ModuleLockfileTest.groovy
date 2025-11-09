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
 * Test suite for ModuleLockfile
 *
 * Tests cryptographic integrity verification inspired by:
 * - Go's go.sum
 * - Deno's deno.lock
 * - npm's package-lock.json
 *
 * @author Edmund Miller <edmund.a.miller@gmail.com>
 */
class ModuleLockfileTest extends Specification {

    Path tempDir

    def setup() {
        tempDir = Files.createTempDirectory('lockfile-test')
    }

    def cleanup() {
        tempDir?.deleteDir()
    }

    def 'should create empty lockfile'() {
        when:
        def lockfile = new ModuleLockfile(tempDir)

        then:
        lockfile.getAllEntries().isEmpty()
    }

    def 'should calculate SHA-256 hash of file'() {
        given:
        def testFile = tempDir.resolve('test.txt')
        testFile.text = 'Hello, World!'

        when:
        def hash = ModuleLockfile.calculateFileHash(testFile)

        then:
        hash != null
        hash.length() == 64  // SHA-256 produces 64 hex characters
        // Known SHA-256 of "Hello, World!"
        hash == 'dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f'
    }

    def 'should calculate deterministic directory hash'() {
        given:
        def dir = tempDir.resolve('module')
        Files.createDirectories(dir)
        dir.resolve('file1.txt').text = 'content1'
        dir.resolve('file2.txt').text = 'content2'
        def subdir = dir.resolve('subdir')
        Files.createDirectories(subdir)
        subdir.resolve('file3.txt').text = 'content3'

        when:
        def hash1 = ModuleLockfile.calculateDirectoryHash(dir)
        def hash2 = ModuleLockfile.calculateDirectoryHash(dir)

        then:
        hash1 == hash2  // Deterministic
        hash1.length() == 64
    }

    def 'should detect modified file in directory'() {
        given:
        def dir = tempDir.resolve('module')
        Files.createDirectories(dir)
        dir.resolve('file1.txt').text = 'content1'
        dir.resolve('file2.txt').text = 'content2'

        when:
        def hash1 = ModuleLockfile.calculateDirectoryHash(dir)
        dir.resolve('file1.txt').text = 'modified'  // Modify file
        def hash2 = ModuleLockfile.calculateDirectoryHash(dir)

        then:
        hash1 != hash2  // Hashes should differ
    }

    def 'should add entry to lockfile'() {
        given:
        def lockfile = new ModuleLockfile(tempDir)
        def moduleDir = tempDir.resolve('modules/test')
        Files.createDirectories(moduleDir)
        moduleDir.resolve('main.nf').text = 'process TEST { }'

        when:
        lockfile.addEntry('testmodule', 'github:test/repo', 'path/to/module', 'abc123', moduleDir)

        then:
        lockfile.hasEntry('testmodule')
        def entry = lockfile.getEntry('testmodule')
        entry.source == 'github:test/repo'
        entry.path == 'path/to/module'
        entry.revision == 'abc123'
        entry.integrity != null
        entry.integrity.length() == 64
    }

    def 'should save and load lockfile'() {
        given:
        def lockfile1 = new ModuleLockfile(tempDir)
        def moduleDir = tempDir.resolve('modules/test')
        Files.createDirectories(moduleDir)
        moduleDir.resolve('main.nf').text = 'process TEST { }'

        when:
        lockfile1.addEntry('testmodule', 'github:test/repo', 'path', 'abc123', moduleDir)
        def integrity = lockfile1.getEntry('testmodule').integrity

        and:
        def lockfile2 = new ModuleLockfile(tempDir)

        then:
        lockfile2.hasEntry('testmodule')
        lockfile2.getEntry('testmodule').integrity == integrity
    }

    def 'should verify intact module integrity'() {
        given:
        def lockfile = new ModuleLockfile(tempDir)
        def moduleDir = tempDir.resolve('modules/test')
        Files.createDirectories(moduleDir)
        moduleDir.resolve('main.nf').text = 'process TEST { }'

        when:
        lockfile.addEntry('testmodule', 'github:test/repo', 'path', 'abc123', moduleDir)

        then:
        lockfile.verifyIntegrity('testmodule', moduleDir)
    }

    def 'should fail integrity check for modified module'() {
        given:
        def lockfile = new ModuleLockfile(tempDir)
        def moduleDir = tempDir.resolve('modules/test')
        Files.createDirectories(moduleDir)
        moduleDir.resolve('main.nf').text = 'process TEST { }'

        when:
        lockfile.addEntry('testmodule', 'github:test/repo', 'path', 'abc123', moduleDir)

        and: 'modify the module'
        moduleDir.resolve('main.nf').text = 'process MODIFIED { }'

        then:
        !lockfile.verifyIntegrity('testmodule', moduleDir)
    }

    def 'should fail integrity check for missing module'() {
        given:
        def lockfile = new ModuleLockfile(tempDir)
        def moduleDir = tempDir.resolve('modules/test')
        Files.createDirectories(moduleDir)
        moduleDir.resolve('main.nf').text = 'process TEST { }'

        when:
        lockfile.addEntry('testmodule', 'github:test/repo', 'path', 'abc123', moduleDir)

        and: 'delete the module'
        moduleDir.deleteDir()

        then:
        !lockfile.verifyIntegrity('testmodule', moduleDir)
    }

    def 'should remove entry from lockfile'() {
        given:
        def lockfile = new ModuleLockfile(tempDir)
        def moduleDir = tempDir.resolve('modules/test')
        Files.createDirectories(moduleDir)
        moduleDir.resolve('main.nf').text = 'process TEST { }'

        when:
        lockfile.addEntry('testmodule', 'github:test/repo', 'path', 'abc123', moduleDir)

        then:
        lockfile.hasEntry('testmodule')

        when:
        lockfile.removeEntry('testmodule')

        then:
        !lockfile.hasEntry('testmodule')
    }

    def 'should track individual file hashes'() {
        given:
        def lockfile = new ModuleLockfile(tempDir)
        def moduleDir = tempDir.resolve('modules/test')
        Files.createDirectories(moduleDir)
        moduleDir.resolve('main.nf').text = 'process TEST { }'
        moduleDir.resolve('meta.yml').text = 'name: test'

        when:
        lockfile.addEntry('testmodule', 'github:test/repo', 'path', 'abc123', moduleDir)
        def entry = lockfile.getEntry('testmodule')

        then:
        entry.files.size() == 2
        entry.files.containsKey('main.nf')
        entry.files.containsKey('meta.yml')
        entry.files['main.nf'].length() == 64
        entry.files['meta.yml'].length() == 64
    }

    def 'should convert entry to and from JSON'() {
        given:
        def entry = new ModuleLockfile.LockEntry(
            source: 'github:test/repo',
            path: 'path/to/module',
            revision: 'abc123',
            integrity: '1234567890abcdef' * 4,
            timestamp: 1234567890L,
            files: ['main.nf': 'deadbeef' * 8]
        )

        when:
        def json = entry.toJson()
        def restored = ModuleLockfile.LockEntry.fromJson(json)

        then:
        restored.source == entry.source
        restored.path == entry.path
        restored.revision == entry.revision
        restored.integrity == entry.integrity
        restored.timestamp == entry.timestamp
        restored.files == entry.files
    }

    def 'should handle corrupted lockfile gracefully'() {
        given:
        def lockfilePath = tempDir.resolve('modules.lock')
        lockfilePath.text = 'invalid json {'

        when:
        new ModuleLockfile(tempDir)

        then:
        thrown(AbortOperationException)
    }

    def 'should get all entries'() {
        given:
        def lockfile = new ModuleLockfile(tempDir)
        def module1Dir = tempDir.resolve('modules/test1')
        def module2Dir = tempDir.resolve('modules/test2')
        Files.createDirectories(module1Dir)
        Files.createDirectories(module2Dir)
        module1Dir.resolve('main.nf').text = 'process TEST1 { }'
        module2Dir.resolve('main.nf').text = 'process TEST2 { }'

        when:
        lockfile.addEntry('module1', 'github:test/repo1', 'path1', 'abc', module1Dir)
        lockfile.addEntry('module2', 'github:test/repo2', 'path2', 'def', module2Dir)

        then:
        lockfile.getAllEntries().size() == 2
        lockfile.getAllEntries().containsKey('module1')
        lockfile.getAllEntries().containsKey('module2')
    }

    def 'should persist lockfile to disk'() {
        given:
        def lockfile = new ModuleLockfile(tempDir)
        def moduleDir = tempDir.resolve('modules/test')
        Files.createDirectories(moduleDir)
        moduleDir.resolve('main.nf').text = 'process TEST { }'

        when:
        lockfile.addEntry('testmodule', 'github:test/repo', 'path', 'abc123', moduleDir)

        then:
        Files.exists(tempDir.resolve('modules.lock'))
        def content = tempDir.resolve('modules.lock').text
        content.contains('testmodule')
        content.contains('github:test/repo')
        content.contains('abc123')
    }

    def 'should handle empty lockfile file'() {
        given:
        tempDir.resolve('modules.lock').text = '{}'

        when:
        def lockfile = new ModuleLockfile(tempDir)

        then:
        lockfile.getAllEntries().isEmpty()
    }
}
