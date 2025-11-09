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

package nextflow.cli

import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.exception.AbortOperationException
import nextflow.module.ModuleManager

/**
 * CLI command for managing Nextflow modules
 *
 * Provides subcommands for installing, listing, updating, and removing modules
 * from remote repositories (similar to nf-core modules)
 *
 * @author Edmund Miller <edmund.a.miller@gmail.com>
 */
@Slf4j
@CompileStatic
@Parameters(commandDescription = "Manage remote Nextflow modules")
class CmdModules extends CmdBase {

    @Parameter(description = 'subcommand', arity = 0..1)
    List<String> args = []

    @Parameter(names = ['-h', '--help'], description = 'Print this help', help = true)
    boolean help

    @Override
    String getName() {
        return 'modules'
    }

    @Override
    void run() {
        if (help || !args) {
            printHelp()
            return
        }

        def subcommand = args[0]
        switch (subcommand) {
            case 'install':
                new InstallCmd(args.drop(1)).run()
                break
            case 'list':
                new ListCmd(args.drop(1)).run()
                break
            case 'remove':
                new RemoveCmd(args.drop(1)).run()
                break
            case 'update':
                new UpdateCmd(args.drop(1)).run()
                break
            case 'info':
                new InfoCmd(args.drop(1)).run()
                break
            default:
                println "ERROR: Unknown subcommand: ${subcommand}"
                printHelp()
                System.exit(1)
        }
    }

    private void printHelp() {
        println """
Usage: nextflow modules <subcommand> [options]

Manage remote Nextflow modules from repositories like nf-core.

Available subcommands:
  install <module-reference>  Install a module from a remote repository
  list                        List all installed modules
  info <module-name>          Show information about an installed module
  update <module-name>        Update an installed module
  remove <module-name>        Remove an installed module

Module Reference Format:
  [provider:]owner/repo/path@revision

Examples:
  # Install a module from nf-core
  nextflow modules install github:nf-core/modules/modules/bowtie/align@main

  # Install with short syntax (defaults to github)
  nextflow modules install nf-core/modules/modules/bowtie/align@abc123

  # List installed modules
  nextflow modules list

  # Update a module
  nextflow modules update align

  # Remove a module
  nextflow modules remove align

For more information visit: https://nextflow.io/docs/latest/modules.html
""".stripIndent()
    }

    /**
     * Install subcommand
     */
    @CompileStatic
    static class InstallCmd {
        List<String> args
        boolean force = false

        InstallCmd(List<String> args) {
            this.args = args
            // Parse flags
            this.force = args.contains('--force') || args.contains('-f')
            this.args = args.findAll { !it.startsWith('-') }
        }

        void run() {
            if (args.isEmpty()) {
                println "ERROR: Module reference required"
                println "Usage: nextflow modules install [--force] <module-reference>"
                println "Example: nextflow modules install nf-core/modules/modules/bowtie/align@main"
                System.exit(1)
            }

            def reference = args[0]
            try {
                def manager = new ModuleManager()
                println "Installing module from: ${reference}"

                def installed = manager.install(reference, force)
                println """
✓ Module installed successfully!

  Name:      ${installed.name}
  Source:    ${installed.source}
  Path:      ${installed.path}
  Revision:  ${installed.revision}
  Location:  ${installed.installedPath}

You can now include this module in your workflow:
  include { ${installed.name.toUpperCase()} } from './${installed.installedPath.toString()}/main.nf'
""".stripIndent()

            } catch (AbortOperationException e) {
                println "ERROR: ${e.message}"
                System.exit(1)
            } catch (Exception e) {
                println "ERROR: Failed to install module: ${e.message}"
                log.error("Installation failed", e)
                System.exit(1)
            }
        }
    }

    /**
     * List subcommand
     */
    @CompileStatic
    static class ListCmd {
        List<String> args

        ListCmd(List<String> args) {
            this.args = args
        }

        void run() {
            try {
                def manager = new ModuleManager()
                def modules = manager.list()

                if (modules.isEmpty()) {
                    println "No modules installed."
                    println "Use 'nextflow modules install' to install a module."
                    return
                }

                println "Installed modules:\n"
                println "NAME${' ' * 20}SOURCE${' ' * 35}REVISION"
                println "${'-' * 80}"

                modules.sort { it.name }.each { module ->
                    def name = module.name.padRight(24)
                    def source = "${module.source}/${module.path}".take(39).padRight(40)
                    def revision = module.revision.take(10)
                    println "${name}${source}${revision}"
                }

                println "\nTotal: ${modules.size()} module(s)"

            } catch (Exception e) {
                println "ERROR: ${e.message}"
                log.error("List failed", e)
                System.exit(1)
            }
        }
    }

    /**
     * Info subcommand
     */
    @CompileStatic
    static class InfoCmd {
        List<String> args

        InfoCmd(List<String> args) {
            this.args = args
        }

        void run() {
            if (args.isEmpty()) {
                println "ERROR: Module name required"
                println "Usage: nextflow modules info <module-name>"
                System.exit(1)
            }

            def moduleName = args[0]
            try {
                def manager = new ModuleManager()
                def module = manager.info(moduleName)

                println """
Module: ${module.name}
${'=' * 60}
  Source:           ${module.source}
  Path:             ${module.path}
  Revision:         ${module.revision}
  Installed Path:   ${module.installedPath}

Include Statement:
  include { ${module.name.toUpperCase()} } from './${module.installedPath}/main.nf'
""".stripIndent()

            } catch (AbortOperationException e) {
                println "ERROR: ${e.message}"
                System.exit(1)
            } catch (Exception e) {
                println "ERROR: ${e.message}"
                log.error("Info failed", e)
                System.exit(1)
            }
        }
    }

    /**
     * Update subcommand
     */
    @CompileStatic
    static class UpdateCmd {
        List<String> args

        UpdateCmd(List<String> args) {
            this.args = args
        }

        void run() {
            if (args.isEmpty()) {
                println "ERROR: Module name required"
                println "Usage: nextflow modules update <module-name> [revision]"
                System.exit(1)
            }

            def moduleName = args[0]
            def newRevision = args.size() > 1 ? args[1] : null

            try {
                def manager = new ModuleManager()
                println "Updating module: ${moduleName}"

                def updated = manager.update(moduleName, newRevision)
                println """
✓ Module updated successfully!

  Name:      ${updated.name}
  Revision:  ${updated.revision}
  Location:  ${updated.installedPath}
""".stripIndent()

            } catch (AbortOperationException e) {
                println "ERROR: ${e.message}"
                System.exit(1)
            } catch (Exception e) {
                println "ERROR: Failed to update module: ${e.message}"
                log.error("Update failed", e)
                System.exit(1)
            }
        }
    }

    /**
     * Remove subcommand
     */
    @CompileStatic
    static class RemoveCmd {
        List<String> args

        RemoveCmd(List<String> args) {
            this.args = args
        }

        void run() {
            if (args.isEmpty()) {
                println "ERROR: Module name required"
                println "Usage: nextflow modules remove <module-name>"
                System.exit(1)
            }

            def moduleName = args[0]
            try {
                def manager = new ModuleManager()
                manager.remove(moduleName)
                println "✓ Module '${moduleName}' removed successfully"

            } catch (AbortOperationException e) {
                println "ERROR: ${e.message}"
                System.exit(1)
            } catch (Exception e) {
                println "ERROR: Failed to remove module: ${e.message}"
                log.error("Remove failed", e)
                System.exit(1)
            }
        }
    }
}
