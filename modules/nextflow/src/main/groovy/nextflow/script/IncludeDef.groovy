/*
 * Copyright 2013-2019, Centre for Genomic Regulation (CRG)
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

import java.nio.file.NoSuchFileException
import java.nio.file.Path

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.Memoized
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.exception.IllegalModulePath
import nextflow.module.ModuleManager
import nextflow.module.ModuleLockfile
import nextflow.scm.AssetManager
/**
 * Implements a script inclusion
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
@EqualsAndHashCode
class IncludeDef {

    @Canonical
    static class Module {
        String name
        String alias
    }

    @PackageScope path
    @PackageScope List<Module> modules
    @PackageScope Map params
    @PackageScope Map addedParams
    private Session session

    @Deprecated
    IncludeDef( String module ) {
        log.warn "Anonymous module inclusion is deprecated -- Replace `include '${module}'` with `include { MODULE_NAME } from '${module}'`"
        this.path = module
        this.modules = new ArrayList<>(1)
        this.modules << new Module(null,null)
    }

    IncludeDef(TokenVar name, String alias=null) {
        this.modules = new ArrayList<>(1)
        this.modules << new Module(name.name, alias)
    }

    protected IncludeDef(List<Module> modules) {
        this.modules = new ArrayList<>(modules)
    }

    /** only for testing purpose -- do not use */
    protected IncludeDef() { }

    IncludeDef from(Object path) {
        this.path = path
        return this
    }

    IncludeDef params(Map args) {
        this.params = args != null ? new HashMap(args) : null
        return this
    }

    IncludeDef addParams(Map args) {
        this.addedParams = args
        return this
    }

    IncludeDef setSession(Session session) {
        this.session = session
        return this
    }

    /*
     * Note: this method invocation is injected during the Nextflow AST manipulation.
     * Do not use it explicitly.
     *
     * @param ownerParams The params in the owner context
     */
    void load0(ScriptBinding.ParamsMap ownerParams) {
        checkValidPath(path)
        // -- resolve the concrete against the current script
        final moduleFile = realModulePath(path)
        // -- load the module
        final moduleScript = loadModule0(moduleFile, resolveParams(ownerParams), session)
        // -- add it to the inclusions
        for( Module module : modules ) {
            meta.addModule(moduleScript, module.name, module.alias)
        }
    }

    private Map resolveParams(ScriptBinding.ParamsMap ownerParams) {
        if( params!=null && addedParams!=null )
            throw new IllegalArgumentException("Include 'params' and 'addParams' option conflict -- check module: $path")
        if( params!=null )
            return params

        addedParams ? ownerParams.copyWith(addedParams) : ownerParams
    }

    @PackageScope
    ScriptMeta getMeta() { ScriptMeta.current() }

    @PackageScope
    Path getOwnerPath() { getMeta().getScriptPath() }

    @PackageScope
    @Memoized
    static BaseScript loadModule0(Path path, Map params, Session session) {
        final binding = new ScriptBinding() .setParams(params)

        // the execution of a library file has as side effect the registration of declared processes
        new ScriptParser(session)
                .setModule(true)
                .setBinding(binding)
                .runScript(path)
                .getScript()
    }

    @PackageScope
    Path resolveModulePath(include) {
        assert include

        // Check if this is a remote module URL
        if( include instanceof String && isRemoteModuleUrl(include) ) {
            return resolveRemoteModule(include)
        }

        final result = include as Path
        if( result.isAbsolute() ) {
            if( result.scheme == 'file' ) return result
            // Could be a remote module URL
            if( isRemoteModuleUrl(result.toString()) ) {
                return resolveRemoteModule(result.toString())
            }
            throw new IllegalModulePath("Cannot resolve module path: ${result.toUriString()}")
        }

        return getOwnerPath().resolveSibling(include.toString())
    }

    @PackageScope
    Path realModulePath(include) {
        def module = resolveModulePath(include)

        // check if exists a file with `.nf` extension
        if( !module.name.endsWith('.nf') ) {
            def extendedName = module.resolveSibling( "${module.name}.nf" )
            if( extendedName.exists() )
                return extendedName
        }

        // check the file exists
        if( module.exists() )
            return module

        throw new NoSuchFileException("Can't find a matching module file for include: $include")
    }

    @PackageScope
    void checkValidPath(path) {
        if( !path )
            throw new IllegalModulePath("Missing module path attribute")

        // Remote modules are now allowed (github:, gitlab:, bitbucket:)
        if( path instanceof Path && path.scheme != 'file' ) {
            // This is a remote module - will be resolved later
            return
        }

        final str = path.toString()
        // Allow remote URLs (github:, gitlab:, bitbucket:) or local paths
        if( isRemoteModuleUrl(str) ) {
            return
        }

        if( !str.startsWith('/') && !str.startsWith('./') && !str.startsWith('../') )
            throw new IllegalModulePath("Module path must start with / or ./ prefix -- Offending module: $str")
    }

    @PackageScope
    boolean isRemoteModuleUrl(String path) {
        return path?.startsWith('github:') ||
               path?.startsWith('gitlab:') ||
               path?.startsWith('bitbucket:')
    }

    @PackageScope
    Path resolveRemoteModule(String remotePath) {
        log.info "Resolving remote module: ${remotePath}"

        // Parse module reference (e.g., "github:nf-core/modules/modules/bowtie/align@abc123")
        def ref = ModuleManager.ModuleReference.parse(remotePath)

        // Use AssetManager to download repository (same as 'nextflow run github:...')
        def project = "${ref.provider}:${ref.project}"
        def assetManager = new AssetManager(project)

        try {
            // Download repository at specified revision
            log.debug "Downloading repository ${project}@${ref.revision}"
            assetManager.download(ref.revision)

            // Get local path to downloaded repository
            def repoPath = assetManager.getLocalPath()
            log.debug "Repository downloaded to ${repoPath}"

            // Locate module file in repository
            def modulePath = repoPath.resolve(ref.path)
            def moduleFile = modulePath.resolve('main.nf')

            if( !moduleFile.exists() ) {
                throw new IllegalModulePath("Module file not found: ${moduleFile}")
            }

            // Add to lockfile with integrity hash (if session available)
            if( session?.baseDir ) {
                def lockfile = new ModuleLockfile(session.baseDir)

                // Verify integrity if already locked
                if( lockfile.hasEntry(ref.moduleName) ) {
                    def verified = lockfile.verifyIntegrity(ref.moduleName, modulePath)
                    if( !verified ) {
                        def securityMode = session.config?.navigate('modules.security', 'strict')
                        handleIntegrityFailure(ref.moduleName, securityMode as String)
                    }
                } else {
                    // First time - add to lockfile
                    lockfile.addEntry(
                        ref.moduleName,
                        "${ref.provider}:${ref.project}",
                        ref.path,
                        ref.revision,
                        modulePath
                    )
                    log.info "Module ${ref.moduleName} locked with integrity hash"
                }
            }

            return moduleFile

        } catch (Exception e) {
            throw new IllegalModulePath("Failed to resolve remote module ${remotePath}: ${e.message}", e)
        }
    }

    @PackageScope
    void handleIntegrityFailure(String moduleName, String securityMode) {
        def message = """
Module integrity verification FAILED for '${moduleName}'

  This may indicate:
  - Module files were modified locally
  - Corruption or tampering
  - Different module version

  To fix:
  - Delete the module directory and re-run to re-download
  - Or set security mode to 'warn' in nextflow.config

  Security mode: ${securityMode ?: 'strict'}
""".stripIndent()

        switch (securityMode) {
            case 'strict':
                throw new IllegalModulePath(message)
            case 'warn':
                log.warn message
                break
            case 'permissive':
                log.debug message
                break
            default:
                log.warn "Unknown security mode '${securityMode}', using 'strict'"
                throw new IllegalModulePath(message)
        }
    }


}
