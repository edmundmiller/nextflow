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
import nextflow.test.TestRunner

/**
 * CLI sub-command TEST
 *
 * Runs .nf.test files for testing Nextflow processes and workflows
 *
 * @author Edmund Miller
 */
@Slf4j
@CompileStatic
@Parameters(commandDescription = "Run nf-test test files")
class CmdTest extends CmdBase {

    @Parameter(description = 'Paths to test files or directories')
    List<String> args = []

    @Parameter(names = ['-filter', '--filter'], description = 'Filter tests by name pattern')
    String filter

    @Parameter(names = ['-profile', '--profile'], description = 'Nextflow profile(s) to use')
    List<String> profiles = []

    @Parameter(names = ['-debug', '--debug'], description = 'Enable debug output')
    boolean debug

    @Parameter(names = ['-tap', '--tap'], description = 'Output in TAP format')
    boolean tapOutput

    @Override
    String getName() { 'test' }

    @Override
    void run() {
        // Default to current directory if no paths specified
        if (!args) {
            args = ['.']
        }

        log.info "Starting nf-test runner"
        log.debug "Test paths: ${args}"
        log.debug "Filter: ${filter}"
        log.debug "Debug: ${debug}"

        def runner = new TestRunner()
            .filter(filter)
            .debug(debug)

        def result = runner.run(args)

        // Print summary
        runner.printSummary()

        // Exit with error if tests failed
        if (!result.success) {
            throw new AbortOperationException("${result.failedTests} test(s) failed")
        }
    }
}
