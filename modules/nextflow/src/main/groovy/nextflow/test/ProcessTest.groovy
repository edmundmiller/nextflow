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

import java.nio.file.Files
import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Global
import nextflow.Session
import nextflow.script.BaseScript
import nextflow.script.ChannelOut
import nextflow.script.ProcessDef
import nextflow.script.ScriptBinding
import nextflow.script.ScriptFile
import nextflow.script.parser.v1.ScriptLoaderV1
import nextflow.script.ScriptMeta

/**
 * Individual process test
 *
 * @author Edmund Miller
 */
@Slf4j
class ProcessTest implements Test {

    private ProcessTestSuite parent
    private String testName
    private Closure whenClosure
    private Closure thenClosure
    private Closure setupClosure
    private Closure cleanupClosure

    // Execution context
    private TestExecutionContext context

    ProcessTest(ProcessTestSuite parent, String name) {
        this.parent = parent
        this.testName = name
    }

    /**
     * DSL method: define when block
     */
    void when(@DelegatesTo(value = WhenContext, strategy = Closure.DELEGATE_FIRST) Closure closure) {
        this.whenClosure = closure
    }

    /**
     * DSL method: define then block
     */
    void then(Closure closure) {
        this.thenClosure = closure
    }

    /**
     * DSL method: define setup block
     */
    void setup(Closure closure) {
        this.setupClosure = closure
    }

    /**
     * DSL method: define cleanup block
     */
    void cleanup(Closure closure) {
        this.cleanupClosure = closure
    }

    @Override
    String getName() { testName }

    @Override
    TestResult execute() {
        log.info "Executing test: ${testName}"
        long startTime = System.currentTimeMillis()

        try {
            // Initialize execution context
            context = new TestExecutionContext(parent)
            context.initialize()

            // Run setup
            if (setupClosure) {
                setupClosure.delegate = context
                setupClosure.call()
            }

            // Execute when block to capture inputs
            if (whenClosure) {
                def whenCtx = new WhenContext(context)
                whenClosure.delegate = whenCtx
                whenClosure.resolveStrategy = Closure.DELEGATE_FIRST
                whenClosure.call()
            }

            // Load and execute the process
            executeProcess()

            // Execute then block (assertions)
            if (thenClosure) {
                def thenCtx = new ThenContext(context)
                thenClosure.delegate = thenCtx
                thenClosure.resolveStrategy = Closure.DELEGATE_FIRST
                thenClosure.call()
            }

            long duration = System.currentTimeMillis() - startTime
            return TestResult.passed(testName, duration)

        } catch (Throwable e) {
            log.error "Test failed: ${testName}", e
            long duration = System.currentTimeMillis() - startTime
            return TestResult.failed(testName, e, duration)

        } finally {
            // Run cleanup
            if (cleanupClosure) {
                try {
                    cleanupClosure.delegate = context
                    cleanupClosure.call()
                } catch (Exception e) {
                    log.warn "Cleanup failed for test: ${testName}", e
                }
            }

            // Cleanup session
            context?.cleanup()
        }
    }

    /**
     * Load and execute the target process
     */
    private void executeProcess() {
        Session session = context.session

        // Load the module containing the process
        Path scriptPath = parent.resolveScript()
        log.debug "Loading script: ${scriptPath}"

        ScriptBinding binding = new ScriptBinding().setParams(context.params)
        BaseScript moduleScript = new ScriptLoaderV1(session)
            .setModule(true)
            .setBinding(binding)
            .parse(scriptPath)
            .runScript()

        // Get the process definition
        ScriptMeta meta = ScriptMeta.get(moduleScript)
        ProcessDef processDef = meta.getProcess(parent.processName)

        if (processDef == null) {
            throw new IllegalStateException("Process '${parent.processName}' not found in script: ${scriptPath}")
        }

        log.debug "Found process: ${processDef.name}"

        // Invoke the process with captured inputs
        Object[] inputs = context.inputContext.toArray()
        log.debug "Invoking process with ${inputs.length} inputs"

        ChannelOut output = (ChannelOut) processDef.run(inputs)

        // Store output in context for assertions
        context.processOutput = output

        // Fire the dataflow network and wait for completion
        session.fireDataflowNetwork()
        session.await()

        // Record success/failure based on session state
        context.processSuccess = session.isSuccess()
        context.processExitStatus = session.isSuccess() ? 0 : 1
    }
}

/**
 * Context for test execution
 */
@Slf4j
class TestExecutionContext {

    ProcessTestSuite suite
    Session session
    Map<String, Object> params = [:]
    InputContext inputContext = new InputContext()
    Path workDir
    Path outputDir

    // Process results
    ChannelOut processOutput
    boolean processSuccess
    int processExitStatus

    TestExecutionContext(ProcessTestSuite suite) {
        this.suite = suite
    }

    void initialize() {
        // Create temp directories
        workDir = Files.createTempDirectory('nf-test-work')
        outputDir = Files.createTempDirectory('nf-test-output')

        // Create session
        Map config = [
            workDir: workDir.toString()
        ]

        if (suite.configFile?.exists()) {
            // Load config file
            log.debug "Loading config: ${suite.configFile}"
        }

        session = new Session(config)
        ScriptFile scriptFile = new ScriptFile(suite.resolveScript().toFile())
        session.init(scriptFile)
        session.start()

        // Store session globally for process access
        Global.session = session
    }

    void cleanup() {
        try {
            session?.abort()
        } catch (Exception e) {
            log.debug "Session cleanup error: ${e.message}"
        }
    }
}

/**
 * Context for 'when' block
 */
@Slf4j
class WhenContext {

    private TestExecutionContext context

    /** Input context exposed as 'input' in DSL */
    InputContext input

    WhenContext(TestExecutionContext context) {
        this.context = context
        this.input = context.inputContext
    }

    /**
     * DSL method: params { }
     */
    void params(@DelegatesTo(value = Map, strategy = Closure.DELEGATE_FIRST) Closure closure) {
        Map paramsMap = context.params
        closure.delegate = paramsMap
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
    }

    /**
     * DSL method: process { } - for setting inputs
     * Supports both new syntax and legacy """ string syntax
     */
    void process(Closure closure) {
        // Set up delegate to capture input assignments
        closure.delegate = this
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        def result = closure.call()

        // Legacy support: if closure returns a string, it's the old """ mapping syntax
        if (result instanceof String) {
            log.debug "Detected legacy string mapping syntax"
            handleLegacyMapping(result as String)
        }
    }

    /**
     * Handle legacy """ mapping syntax for backward compatibility
     */
    private void handleLegacyMapping(String mapping) {
        // Parse the mapping string and extract input assignments
        // Format: input[0] = Channel.of(...)\n input[1] = ...
        GroovyShell shell = new GroovyShell()
        shell.setVariable('input', input)
        shell.setVariable('Channel', nextflow.Channel)
        shell.evaluate(mapping)
    }

    /**
     * Variables available in when block
     */
    String getOutputDir() {
        context.outputDir.toString()
    }

    String getWorkDir() {
        context.workDir.toString()
    }
}

/**
 * Context for 'then' block - provides assertion helpers
 */
@Slf4j
class ThenContext {

    private TestExecutionContext context

    /** Process result wrapper */
    ProcessResult process

    ThenContext(TestExecutionContext context) {
        this.context = context
        this.process = new ProcessResult(context)
    }

    /**
     * Helper: create path
     */
    Path path(String pathStr) {
        Path.of(pathStr)
    }

    /**
     * Helper: create file
     */
    File file(String pathStr) {
        new File(pathStr)
    }
}

/**
 * Wrapper for process results in 'then' block
 */
@Slf4j
class ProcessResult {

    private TestExecutionContext context

    ProcessResult(TestExecutionContext context) {
        this.context = context
    }

    boolean getSuccess() {
        context.processSuccess
    }

    boolean getFailed() {
        !context.processSuccess
    }

    int getExitStatus() {
        context.processExitStatus
    }

    /**
     * Get output channels
     */
    ChannelOut getOut() {
        if (context.processOutput == null) {
            throw new IllegalStateException("Process has not been executed yet")
        }
        context.processOutput
    }
}
