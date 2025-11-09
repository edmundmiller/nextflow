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
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.exception.AbortOperationException
import nextflow.scm.AssetManager

/**
 * Manages Nextflow module installation, tracking, and lifecycle
 *
 * @author Edmund Miller <edmund.a.miller@gmail.com>
 */
@Slf4j
@CompileStatic
class ModuleManager {

    /**
     * Reference to a remote module
     */
    static class ModuleReference {
        String provider = 'github'
        String owner
        String repository
        String path
        String revision
        String moduleName

        static ModuleReference parse(String reference) {
            // Format: [provider:]owner/repo/path@revision
            // Examples:
            //   github:nf-core/modules/modules/bowtie/align@main
            //   nf-core/modules/modules/bowtie/align@abc123

            def parts = reference.split('@')
            if (parts.length != 2) {
                throw new AbortOperationException("Invalid module reference format. Expected: [provider:]owner/repo/path@revision")
            }

            def revision = parts[1]
            def fullPath = parts[0]

            def provider = 'github'
            if (fullPath.contains(':')) {
                def providerParts = fullPath.split(':', 2)
                provider = providerParts[0]
                fullPath = providerParts[1]
            }

            def pathParts = fullPath.split('/')
            if (pathParts.length < 3) {
                throw new AbortOperationException("Invalid module path. Expected at least: owner/repo/path")
            }

            def owner = pathParts[0]
            def repo = pathParts[1]
            def modulePath = pathParts[2..-1].join('/')

            // Extract module name from path (last component)
            def moduleName = pathParts[-1]

            new ModuleReference(
                provider: provider,
                owner: owner,
                repository: repo,
                path: modulePath,
                revision: revision,
                moduleName: moduleName
            )
        }

        String getProject() {
            return "${owner}/${repository}"
        }

        String getRepositoryUrl() {
            switch (provider) {
                case 'github':
                    return "https://github.com/${project}"
                case 'gitlab':
                    return "https://gitlab.com/${project}"
                case 'bitbucket':
                    return "https://bitbucket.org/${project}"
                default:
                    throw new AbortOperationException("Unsupported provider: ${provider}")
            }
        }

        String toString() {
            return "${provider}:${owner}/${repository}/${path}@${revision}"
        }
    }

    /**
     * Represents an installed module
     */
    static class InstalledModule {
        String name
        String source
        String path
        String revision
        Path installedPath

        Map toJson() {
            return [
                source: source,
                path: path,
                revision: revision,
                installedPath: installedPath.toString()
            ]
        }

        static InstalledModule fromJson(String name, Map json) {
            new InstalledModule(
                name: name,
                source: json.source as String,
                path: json.path as String,
                revision: json.revision as String,
                installedPath: Paths.get(json.installedPath as String)
            )
        }
    }

    private Path modulesRoot
    private Path configFile
    private Map<String, InstalledModule> installedModules = [:]

    ModuleManager(Path projectRoot = Paths.get(System.getProperty('user.dir'))) {
        this.modulesRoot = projectRoot.resolve('modules')
        this.configFile = projectRoot.resolve('modules.json')
        loadConfig()
    }

    /**
     * Load modules.json configuration
     */
    private void loadConfig() {
        if (!Files.exists(configFile)) {
            log.debug "No modules.json found at ${configFile}"
            installedModules = [:]
            return
        }

        try {
            def slurper = new JsonSlurper()
            def json = slurper.parse(configFile.toFile()) as Map
            def modules = json.modules as Map ?: [:]

            installedModules = modules.collectEntries { String name, Map data ->
                [(name): InstalledModule.fromJson(name, data)]
            } as Map<String, InstalledModule>

            log.debug "Loaded ${installedModules.size()} modules from config"
        } catch (Exception e) {
            throw new AbortOperationException("Failed to parse modules.json: ${e.message}", e)
        }
    }

    /**
     * Save modules.json configuration
     */
    private void saveConfig() {
        try {
            def config = [
                modules: installedModules.collectEntries { name, module ->
                    [(name): module.toJson()]
                }
            ]

            def json = JsonOutput.prettyPrint(JsonOutput.toJson(config))
            Files.createDirectories(configFile.parent)
            configFile.toFile().text = json
            log.debug "Saved modules.json with ${installedModules.size()} modules"
        } catch (Exception e) {
            throw new AbortOperationException("Failed to save modules.json: ${e.message}", e)
        }
    }

    /**
     * Install a module from a remote reference
     */
    InstalledModule install(String reference, boolean force = false) {
        def moduleRef = ModuleReference.parse(reference)

        log.info "Installing module: ${moduleRef}"

        // Check if already installed
        if (!force && installedModules.containsKey(moduleRef.moduleName)) {
            def existing = installedModules[moduleRef.moduleName]
            if (existing.revision == moduleRef.revision) {
                log.info "Module ${moduleRef.moduleName} already installed at revision ${moduleRef.revision}"
                return existing
            }
        }

        // Create asset manager for the repository
        def project = "${moduleRef.provider}:${moduleRef.project}"
        def assetManager = new AssetManager(project)

        try {
            // Download the repository at the specified revision
            log.debug "Downloading repository ${project}@${moduleRef.revision}"
            assetManager.download(moduleRef.revision)

            // Get the local path to the downloaded repository
            def repoPath = assetManager.getLocalPath()
            log.debug "Repository downloaded to ${repoPath}"

            // Locate the module file in the downloaded repository
            def moduleSourcePath = repoPath.resolve(moduleRef.path)
            def moduleFile = moduleSourcePath.resolve('main.nf')

            if (!Files.exists(moduleFile)) {
                throw new AbortOperationException("Module file not found: ${moduleFile}")
            }

            // Determine installation path
            def installPath = modulesRoot.resolve("${moduleRef.owner}/${moduleRef.repository}/${moduleRef.path}")
            Files.createDirectories(installPath.parent)

            // Copy the module directory
            log.debug "Copying module from ${moduleSourcePath} to ${installPath}"
            copyDirectory(moduleSourcePath, installPath)

            // Create installed module record
            def installed = new InstalledModule(
                name: moduleRef.moduleName,
                source: "${moduleRef.provider}:${moduleRef.project}",
                path: moduleRef.path,
                revision: moduleRef.revision,
                installedPath: installPath
            )

            installedModules[moduleRef.moduleName] = installed
            saveConfig()

            log.info "Successfully installed module ${moduleRef.moduleName} to ${installPath}"
            return installed

        } catch (Exception e) {
            throw new AbortOperationException("Failed to install module ${moduleRef}: ${e.message}", e)
        }
    }

    /**
     * Recursively copy directory contents
     */
    private void copyDirectory(Path source, Path target) {
        if (!Files.exists(source)) {
            throw new AbortOperationException("Source directory does not exist: ${source}")
        }

        Files.walk(source).each { sourcePath ->
            def targetPath = target.resolve(source.relativize(sourcePath))
            if (Files.isDirectory(sourcePath)) {
                Files.createDirectories(targetPath)
            } else {
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /**
     * List all installed modules
     */
    List<InstalledModule> list() {
        return new ArrayList<>(installedModules.values())
    }

    /**
     * Get information about a specific installed module
     */
    InstalledModule info(String moduleName) {
        def module = installedModules[moduleName]
        if (!module) {
            throw new AbortOperationException("Module not found: ${moduleName}")
        }
        return module
    }

    /**
     * Remove an installed module
     */
    void remove(String moduleName) {
        def module = installedModules[moduleName]
        if (!module) {
            throw new AbortOperationException("Module not found: ${moduleName}")
        }

        log.info "Removing module: ${moduleName}"

        // Delete the module directory
        if (Files.exists(module.installedPath)) {
            deleteDirectory(module.installedPath)
        }

        // Remove from tracking
        installedModules.remove(moduleName)
        saveConfig()

        log.info "Successfully removed module ${moduleName}"
    }

    /**
     * Recursively delete directory
     */
    private void deleteDirectory(Path directory) {
        if (Files.exists(directory)) {
            Files.walk(directory)
                .sorted(Comparator.reverseOrder())
                .each { Files.delete(it) }
        }
    }

    /**
     * Update a module to a new revision
     */
    InstalledModule update(String moduleName, String newRevision = null) {
        def module = installedModules[moduleName]
        if (!module) {
            throw new AbortOperationException("Module not found: ${moduleName}")
        }

        log.info "Updating module: ${moduleName}"

        // Construct new reference
        def reference = newRevision
            ? "${module.source}/${module.path}@${newRevision}"
            : "${module.source}/${module.path}@${module.revision}"

        // Reinstall with force flag
        return install(reference, true)
    }

    /**
     * Check if a module is installed
     */
    boolean isInstalled(String moduleName) {
        return installedModules.containsKey(moduleName)
    }
}
