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
import java.security.MessageDigest

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.exception.AbortOperationException

/**
 * Module lockfile manager with cryptographic integrity verification
 *
 * Similar to:
 * - Go's go.sum (SHA-256 checksums)
 * - Deno's deno.lock (integrity hashes)
 * - npm's package-lock.json
 *
 * Provides:
 * - Content integrity verification
 * - Reproducible builds
 * - Tamper detection
 * - Version pinning
 *
 * @author Edmund Miller <edmund.a.miller@gmail.com>
 */
@Slf4j
@CompileStatic
class ModuleLockfile {

    static class LockEntry {
        String source
        String path
        String revision
        String integrity  // SHA-256 hash of module content
        long timestamp
        Map<String, String> files = [:]  // filename -> sha256

        Map toJson() {
            return [
                source: source,
                path: path,
                revision: revision,
                integrity: integrity,
                timestamp: timestamp,
                files: files
            ]
        }

        static LockEntry fromJson(Map json) {
            new LockEntry(
                source: json.source as String,
                path: json.path as String,
                revision: json.revision as String,
                integrity: json.integrity as String,
                timestamp: json.timestamp as Long ?: System.currentTimeMillis(),
                files: (json.files ?: [:]) as Map<String, String>
            )
        }
    }

    private Path lockfilePath
    private Map<String, LockEntry> entries = [:]

    ModuleLockfile(Path projectRoot) {
        this.lockfilePath = projectRoot.resolve('modules.lock')
        load()
    }

    /**
     * Load lockfile from disk
     */
    private void load() {
        if (!Files.exists(lockfilePath)) {
            log.debug "No modules.lock found"
            entries = [:]
            return
        }

        try {
            def slurper = new JsonSlurper()
            def json = slurper.parse(lockfilePath.toFile()) as Map
            def version = json.version as Integer ?: 1
            def modules = json.modules as Map ?: [:]

            entries = modules.collectEntries { String name, Map data ->
                [(name): LockEntry.fromJson(data)]
            } as Map<String, LockEntry>

            log.debug "Loaded ${entries.size()} entries from modules.lock (version ${version})"
        } catch (Exception e) {
            throw new AbortOperationException("Failed to parse modules.lock: ${e.message}", e)
        }
    }

    /**
     * Save lockfile to disk
     */
    void save() {
        try {
            def lockData = [
                version: 1,
                generated: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'"),
                modules: entries.collectEntries { name, entry ->
                    [(name): entry.toJson()]
                }
            ]

            def json = JsonOutput.prettyPrint(JsonOutput.toJson(lockData))
            Files.createDirectories(lockfilePath.parent)
            lockfilePath.toFile().text = json
            log.debug "Saved modules.lock with ${entries.size()} entries"
        } catch (Exception e) {
            throw new AbortOperationException("Failed to save modules.lock: ${e.message}", e)
        }
    }

    /**
     * Calculate SHA-256 hash of directory contents
     */
    static String calculateDirectoryHash(Path directory) {
        def digest = MessageDigest.getInstance('SHA-256')
        def files = []

        // Collect all files with their relative paths (sorted for deterministic hash)
        Files.walk(directory)
            .filter { !Files.isDirectory(it) }
            .each { files.add(directory.relativize(it)) }

        files.sort().each { relativePath ->
            def file = directory.resolve(relativePath)
            // Hash filename
            digest.update(relativePath.toString().bytes)
            // Hash file content
            digest.update(Files.readAllBytes(file))
        }

        return digest.digest().encodeHex().toString()
    }

    /**
     * Calculate SHA-256 hash of file
     */
    static String calculateFileHash(Path file) {
        def digest = MessageDigest.getInstance('SHA-256')
        digest.update(Files.readAllBytes(file))
        return digest.digest().encodeHex().toString()
    }

    /**
     * Add or update entry in lockfile
     */
    void addEntry(String moduleName, String source, String path, String revision, Path installedPath) {
        def integrity = calculateDirectoryHash(installedPath)

        // Calculate hash for each file
        def fileHashes = [:]
        Files.walk(installedPath)
            .filter { !Files.isDirectory(it) }
            .each { filePath ->
                def relative = installedPath.relativize(filePath).toString()
                fileHashes[relative] = calculateFileHash(filePath)
            }

        def entry = new LockEntry(
            source: source,
            path: path,
            revision: revision,
            integrity: integrity,
            timestamp: System.currentTimeMillis(),
            files: fileHashes
        )

        entries[moduleName] = entry
        save()

        log.info "Locked module ${moduleName} with integrity hash ${integrity.take(16)}..."
    }

    /**
     * Verify integrity of installed module
     */
    boolean verifyIntegrity(String moduleName, Path installedPath) {
        def entry = entries[moduleName]
        if (!entry) {
            log.warn "No lock entry found for module ${moduleName}"
            return false
        }

        if (!Files.exists(installedPath)) {
            log.warn "Module ${moduleName} not found at ${installedPath}"
            return false
        }

        def currentHash = calculateDirectoryHash(installedPath)
        def expectedHash = entry.integrity

        if (currentHash != expectedHash) {
            log.error """
Module integrity verification FAILED for ${moduleName}
  Expected: ${expectedHash}
  Got:      ${currentHash}

This may indicate:
  - Module files were modified
  - Corruption or tampering
  - Different module version

Run 'nextflow modules update ${moduleName}' to fix.
""".stripIndent()
            return false
        }

        log.debug "Module ${moduleName} integrity verified: ${currentHash.take(16)}..."
        return true
    }

    /**
     * Check if module is locked
     */
    boolean hasEntry(String moduleName) {
        return entries.containsKey(moduleName)
    }

    /**
     * Get lock entry
     */
    LockEntry getEntry(String moduleName) {
        return entries[moduleName]
    }

    /**
     * Remove entry from lockfile
     */
    void removeEntry(String moduleName) {
        entries.remove(moduleName)
        save()
    }

    /**
     * Get all entries
     */
    Map<String, LockEntry> getAllEntries() {
        return new HashMap<>(entries)
    }
}
