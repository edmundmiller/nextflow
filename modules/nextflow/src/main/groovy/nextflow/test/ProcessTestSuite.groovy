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

package nextflow.test

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Test suite for process tests
 *
 * DSL usage:
 *   nextflow_process {
 *       name "My Process Tests"
 *       script "path/to/main.nf"
 *       process "PROCESS_NAME"
 *
 *       test("Should run successfully") {
 *           when { ... }
 *           then { ... }
 *       }
 *   }
 *
 * @author Edmund Miller
 */
@Slf4j
@CompileStatic
class ProcessTestSuite implements TestSuite {

    private String suiteName = "Process Test Suite"
    private String scriptPath
    private String processName
    private File baseDir
    private List<ProcessTest> tests = []

    // Optionals
    private boolean autoSort = true
    private List<String> profiles = []
    private File configFile

    /**
     * DSL method: set suite name
     */
    void name(String name) {
        this.suiteName = name
    }

    /**
     * DSL method: set script path
     */
    void script(String path) {
        this.scriptPath = path
    }

    /**
     * DSL method: set process name to test
     */
    void process(String name) {
        this.processName = name
    }

    /**
     * DSL method: set base directory
     */
    void baseDir(File dir) {
        this.baseDir = dir
    }

    /**
     * DSL method: disable auto-sorting of outputs
     */
    void autoSort(boolean value) {
        this.autoSort = value
    }

    /**
     * DSL method: add profile
     */
    void profile(String profile) {
        this.profiles << profile
    }

    /**
     * DSL method: set config file
     */
    void config(String path) {
        this.configFile = new File(path)
    }

    /**
     * DSL method: define a test
     */
    void test(String testName,
              @DelegatesTo(value = ProcessTest, strategy = Closure.DELEGATE_FIRST) Closure closure) {
        def test = new ProcessTest(this, testName)
        closure.delegate = test
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
        tests << test
    }

    // Getters
    @Override
    String getName() { suiteName }

    @Override
    String getScript() { scriptPath }

    String getProcessName() { processName }

    File getBaseDir() { baseDir }

    boolean isAutoSort() { autoSort }

    List<String> getProfiles() { profiles }

    File getConfigFile() { configFile }

    @Override
    List<Test> getTests() { tests as List<Test> }

    /**
     * Resolve script path relative to baseDir
     */
    Path resolveScript() {
        if (baseDir != null) {
            return baseDir.toPath().resolve(scriptPath)
        }
        return Path.of(scriptPath)
    }

    /**
     * Execute all tests in this suite
     */
    @Override
    TestResult execute() {
        log.info "Executing test suite: ${suiteName}"

        def results = []
        int passed = 0
        int failed = 0

        for (test in tests) {
            def result = test.execute()
            results << result
            if (result.success) {
                passed++
            } else {
                failed++
            }
        }

        // Return aggregate result
        def success = failed == 0
        return new TestResult(
            name: suiteName,
            success: success,
            message: "${passed} passed, ${failed} failed"
        )
    }

    @Override
    String toString() {
        "ProcessTestSuite(name='${suiteName}', script='${scriptPath}', process='${processName}', tests=${tests.size()})"
    }
}
