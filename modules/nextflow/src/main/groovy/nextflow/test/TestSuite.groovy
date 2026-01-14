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

import groovy.transform.CompileStatic

/**
 * Interface for test suites
 *
 * @author Edmund Miller
 */
@CompileStatic
interface TestSuite {

    /**
     * Get the name of this test suite
     */
    String getName()

    /**
     * Get the script path being tested
     */
    String getScript()

    /**
     * Get all tests in this suite
     */
    List<Test> getTests()

    /**
     * Execute all tests in this suite
     */
    TestResult execute()
}

/**
 * Interface for individual tests
 */
@CompileStatic
interface Test {

    /**
     * Get the test name
     */
    String getName()

    /**
     * Execute this test
     */
    TestResult execute()
}

/**
 * Result of test execution
 */
@CompileStatic
class TestResult {
    String name
    boolean success
    String message
    Throwable error
    long durationMs

    static TestResult passed(String name, long durationMs) {
        new TestResult(name: name, success: true, durationMs: durationMs)
    }

    static TestResult failed(String name, Throwable error, long durationMs) {
        new TestResult(
            name: name,
            success: false,
            message: error.message,
            error: error,
            durationMs: durationMs
        )
    }
}
