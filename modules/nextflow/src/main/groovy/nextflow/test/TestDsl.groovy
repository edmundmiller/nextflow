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
import groovy.util.logging.Slf4j
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

/**
 * DSL parser for .nf.test files
 *
 * Provides static methods that define the test DSL:
 * - nextflow_process { }
 * - nextflow_workflow { }
 * - nextflow_function { }
 * - nextflow_pipeline { }
 *
 * @author Edmund Miller
 */
@Slf4j
@CompileStatic
class TestDsl {

    /**
     * Parse a .nf.test file and return the test suite
     */
    static TestSuite parse(File file) {
        parse(file.text, file.parentFile)
    }

    /**
     * Parse test DSL from string
     */
    static TestSuite parse(String script, File baseDir = null) {
        def config = createCompilerConfig()
        def binding = new TestBinding(baseDir: baseDir)
        def shell = new GroovyShell(TestDsl.classLoader, binding, config)

        log.debug "Parsing test script from ${baseDir}"
        def result = shell.evaluate(script)

        if (result instanceof TestSuite) {
            return result
        }
        throw new IllegalStateException("Test file must contain a nextflow_process, nextflow_workflow, nextflow_function, or nextflow_pipeline block")
    }

    /**
     * Create compiler configuration with necessary imports
     */
    private static CompilerConfiguration createCompilerConfig() {
        def imports = new ImportCustomizer()
        // Static imports for DSL methods
        imports.addStaticStars(TestDsl.name)
        // Common imports
        imports.addImports(
            'java.nio.file.Path',
            'java.nio.file.Paths',
            'nextflow.Channel'
        )

        def config = new CompilerConfiguration()
        config.addCompilationCustomizers(imports)
        return config
    }

    /**
     * DSL method: define a process test suite
     */
    static ProcessTestSuite nextflow_process(
            @DelegatesTo(value = ProcessTestSuite, strategy = Closure.DELEGATE_FIRST) Closure closure) {
        def suite = new ProcessTestSuite()
        closure.delegate = suite
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
        return suite
    }

    /**
     * DSL method: define a workflow test suite (placeholder for future)
     */
    static TestSuite nextflow_workflow(
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST) Closure closure) {
        throw new UnsupportedOperationException("nextflow_workflow not yet implemented")
    }

    /**
     * DSL method: define a function test suite (placeholder for future)
     */
    static TestSuite nextflow_function(
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST) Closure closure) {
        throw new UnsupportedOperationException("nextflow_function not yet implemented")
    }

    /**
     * DSL method: define a pipeline test suite (placeholder for future)
     */
    static TestSuite nextflow_pipeline(
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST) Closure closure) {
        throw new UnsupportedOperationException("nextflow_pipeline not yet implemented")
    }
}

/**
 * Binding for test script evaluation
 */
@CompileStatic
class TestBinding extends Binding {
    File baseDir
}
