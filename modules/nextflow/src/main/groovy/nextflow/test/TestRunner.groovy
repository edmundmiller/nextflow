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

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Test runner - discovers and executes .nf.test files
 *
 * @author Edmund Miller
 */
@Slf4j
class TestRunner {

    /** Test file pattern */
    static final String TEST_PATTERN = "*.nf.test"

    /** Filter for specific tests */
    private String filter

    /** Debug mode */
    private boolean debug

    /** Test results */
    private List<TestResult> results = []

    /** Statistics */
    private int totalTests = 0
    private int passedTests = 0
    private int failedTests = 0

    TestRunner filter(String pattern) {
        this.filter = pattern
        return this
    }

    TestRunner debug(boolean enabled) {
        this.debug = enabled
        return this
    }

    /**
     * Discover and run all tests in the given paths
     */
    TestRunnerResult run(List<String> paths) {
        def testFiles = discoverTestFiles(paths)

        if (testFiles.isEmpty()) {
            log.warn "No test files found"
            return new TestRunnerResult(results: [], totalTests: 0, passedTests: 0, failedTests: 0)
        }

        log.info "Found ${testFiles.size()} test file(s)"

        for (file in testFiles) {
            runTestFile(file)
        }

        return new TestRunnerResult(
            results: results,
            totalTests: totalTests,
            passedTests: passedTests,
            failedTests: failedTests
        )
    }

    /**
     * Discover test files in the given paths
     */
    private List<File> discoverTestFiles(List<String> paths) {
        List<File> testFiles = []

        for (String pathStr in paths) {
            Path path = Path.of(pathStr)

            if (Files.isDirectory(path)) {
                // Walk directory for .nf.test files
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.fileName.toString().endsWith('.nf.test')) {
                            testFiles << file.toFile()
                        }
                        return FileVisitResult.CONTINUE
                    }
                })
            } else if (Files.exists(path) && path.fileName.toString().endsWith('.nf.test')) {
                testFiles << path.toFile()
            }
        }

        return testFiles
    }

    /**
     * Run a single test file
     */
    private void runTestFile(File testFile) {
        log.info "Running: ${testFile.path}"

        try {
            // Parse the test file
            def suite = TestDsl.parse(testFile)

            // Filter tests if specified
            def testsToRun = suite.tests
            if (filter) {
                testsToRun = testsToRun.findAll { it.name.contains(filter) }
            }

            // Run each test
            for (test in testsToRun) {
                totalTests++

                def result = test.execute()
                results << result

                if (result.success) {
                    passedTests++
                    printTestResult(result, true)
                } else {
                    failedTests++
                    printTestResult(result, false)
                }
            }

        } catch (Exception e) {
            log.error "Failed to parse test file: ${testFile}", e
            failedTests++
            results << TestResult.failed(testFile.name, e, 0)
        }
    }

    /**
     * Print test result
     */
    private void printTestResult(TestResult result, boolean passed) {
        def symbol = passed ? "\u2713" : "\u2717"  // ✓ or ✗
        def status = passed ? "PASSED" : "FAILED"
        def duration = "${result.durationMs}ms"

        println "  ${symbol} ${result.name} (${duration})"

        if (!passed && result.error) {
            println "    Error: ${result.message}"
            if (debug && result.error.stackTrace) {
                result.error.stackTrace.take(10).each {
                    println "      at ${it}"
                }
            }
        }
    }

    /**
     * Print summary
     */
    void printSummary() {
        println ""
        println "=" * 60
        println "Test Summary"
        println "=" * 60
        println "Total:  ${totalTests}"
        println "Passed: ${passedTests}"
        println "Failed: ${failedTests}"
        println "=" * 60

        if (failedTests > 0) {
            println ""
            println "Failed tests:"
            results.findAll { !it.success }.each {
                println "  - ${it.name}: ${it.message}"
            }
        }
    }
}

/**
 * Result of running all tests
 */
@CompileStatic
class TestRunnerResult {
    List<TestResult> results
    int totalTests
    int passedTests
    int failedTests

    boolean isSuccess() {
        failedTests == 0
    }
}
