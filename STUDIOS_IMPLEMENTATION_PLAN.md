# Studios Directive - Implementation Plan

## Overview

This document outlines the concrete steps to implement the `studios:` directive in Nextflow, starting with MVP support for Marimo notebooks.

## Files to Modify/Create

### 1. ProcessConfig.groovy
**File**: `/home/user/nextflow/modules/nextflow/src/main/groovy/nextflow/script/ProcessConfig.groovy`

#### Change 1: Add 'studios' to DIRECTIVES list (Line 46)

```groovy
static final public List<String> DIRECTIVES = [
    'accelerator',
    'afterScript',
    // ... existing directives ...
    'stageInMode',
    'stageOutMode',
    'studios'  // ADD THIS LINE
]
```

#### Change 2: Add studios directive methods (After line 771, after publishDir methods)

```groovy
/**
 * Allow user to specify studios directive for notebook execution
 *
 * Examples:
 *   studios notebook: 'analysis.py', type: 'marimo'
 *   studios 'analysis.py'
 *
 * @param params Map of studios configuration
 * @return The ProcessConfig instance itself
 */
ProcessConfig studios(Map params) {
    if (!params.containsKey('notebook') && !params.containsKey('script')) {
        throw new IllegalArgumentException(
            "studios directive requires 'notebook' parameter, e.g.: studios notebook: 'analysis.py'"
        )
    }
    configProperties.put('studios', params)
    return this
}

/**
 * Allow user to specify studios directive with just a notebook path
 *
 * @param notebookPath Path to the notebook file
 * @return The ProcessConfig instance itself
 */
ProcessConfig studios(String notebookPath) {
    configProperties.put('studios', [notebook: notebookPath])
    return this
}

/**
 * Allow user to specify studios directive with named parameters and path
 *
 * @param params Map of studios configuration
 * @param notebookPath Path to the notebook file
 * @return The ProcessConfig instance itself
 */
ProcessConfig studios(Map params, String notebookPath) {
    params.put('notebook', notebookPath)
    configProperties.put('studios', params)
    return this
}
```

---

### 2. TaskConfig.groovy
**File**: `/home/user/nextflow/modules/nextflow/src/main/groovy/nextflow/processor/TaskConfig.groovy`

#### Add getStudios() method (around line 380, after getContainerOptionsMap())

```groovy
/**
 * @return The studios configuration map, or null if not defined
 */
Map getStudios() {
    def value = get('studios')
    if (value instanceof Map) {
        return (Map) value
    } else if (value instanceof CharSequence) {
        // Simple string path converted to map
        return [notebook: value.toString()]
    }
    return null
}
```

---

### 3. StudioHandler.groovy (NEW FILE)
**File**: `/home/user/nextflow/modules/nextflow/src/main/groovy/nextflow/processor/StudioHandler.groovy`

```groovy
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

package nextflow.processor

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.exception.ProcessException
import nextflow.script.params.InParam
import nextflow.script.params.OutParam

import java.nio.file.Path

/**
 * Handles execution of notebooks (Marimo, Jupyter, etc.) in Nextflow processes
 *
 * @author Edmund Miller
 */
@Slf4j
@CompileStatic
class StudioHandler {

    static enum NotebookType {
        MARIMO,
        JUPYTER,
        RMARKDOWN,
        SEQERA
    }

    static enum ExecutionMode {
        RUN,
        EDIT,
        DRAFT
    }

    final Map config
    final TaskRun task

    StudioHandler(Map config, TaskRun task) {
        this.config = config ?: [:]
        this.task = task
    }

    /**
     * Detect notebook type from file extension or explicit type parameter
     */
    NotebookType detectType() {
        String type = config.type as String
        if (type) {
            try {
                return NotebookType.valueOf(type.toUpperCase())
            } catch (IllegalArgumentException e) {
                throw new ProcessException("Invalid notebook type: $type. Valid types: ${NotebookType.values()*.name()}")
            }
        }

        // Auto-detect from extension
        String notebook = config.notebook as String
        if (!notebook) {
            throw new ProcessException("No notebook file specified in studios directive")
        }

        if (notebook.endsWith('.py')) {
            return NotebookType.MARIMO
        } else if (notebook.endsWith('.ipynb')) {
            return NotebookType.JUPYTER
        } else if (notebook.endsWith('.Rmd') || notebook.endsWith('.rmd')) {
            return NotebookType.RMARKDOWN
        }

        throw new ProcessException("Cannot detect notebook type for: $notebook. " +
            "Specify type explicitly, e.g.: studios notebook: '$notebook', type: 'marimo'")
    }

    /**
     * Build the command to execute the notebook
     */
    String buildCommand() {
        def type = detectType()
        def mode = (config.mode as String) ?: 'run'

        switch (type) {
            case NotebookType.MARIMO:
                return buildMarimoCommand(mode)
            case NotebookType.JUPYTER:
                throw new ProcessException("Jupyter notebook support not yet implemented")
            case NotebookType.RMARKDOWN:
                throw new ProcessException("RMarkdown notebook support not yet implemented")
            case NotebookType.SEQERA:
                throw new ProcessException("Seqera Studios support not yet implemented")
            default:
                throw new ProcessException("Notebook type $type not yet implemented")
        }
    }

    /**
     * Build command for executing Marimo notebooks
     */
    private String buildMarimoCommand(String mode) {
        def notebook = config.notebook as String
        def args = buildNotebookArgs()

        if (mode == 'edit') {
            log.warn("Interactive edit mode not supported in workflow execution. Running in 'run' mode instead.")
            // Fall through to run mode
        }

        // Build marimo run command
        def cmd = new StringBuilder("marimo run \"${notebook}\"")

        if (args) {
            cmd.append(" -- ${args}")
        }

        return cmd.toString()
    }

    /**
     * Build arguments to pass to the notebook
     */
    private String buildNotebookArgs() {
        def args = []

        // Always pass the inputs JSON file
        args << "--nxf-inputs inputs.json"

        // Add any custom arguments from config
        def customArgs = config.args
        if (customArgs instanceof List) {
            args.addAll(customArgs.collect { it.toString() })
        } else if (customArgs instanceof Map) {
            customArgs.each { key, value ->
                args << "--${key} ${value}"
            }
        }

        return args.join(' ')
    }

    /**
     * Generate the inputs.json file content with Nextflow context
     */
    Map buildInputsJson() {
        def inputsMap = [:]
        def outputsMap = [:]
        def paramsMap = [:]

        // Collect process inputs
        task.processor.config.getInputs()?.each { InParam param ->
            def name = param.name
            def value = task.context?.get(name)
            if (value != null) {
                inputsMap[name] = serializeValue(value)
            }
        }

        // Collect expected outputs
        task.processor.config.getOutputs()?.each { OutParam param ->
            def name = param.name
            outputsMap[name] = name
        }

        // Get process params (if any)
        // Note: task.config params are process-level, not workflow params
        def taskParams = task.config.get('params')
        if (taskParams instanceof Map) {
            paramsMap = taskParams as Map
        }

        return [
            input: inputsMap,
            output: [
                expected: outputsMap
            ],
            params: paramsMap,
            task: [
                name: task.processor.name,
                workDir: task.workDir.toString(),
                index: task.index,
                attempt: task.config.attempt ?: 1,
                process: task.processor.name
            ],
            workflow: [
                projectDir: task.processor.session.baseDir.toString(),
                launchDir: task.processor.session.launchDir.toString(),
                workDir: task.processor.session.workDir.toString()
            ]
        ]
    }

    /**
     * Serialize values for JSON export (convert Paths to strings, etc.)
     */
    private Object serializeValue(Object value) {
        if (value instanceof Path) {
            return value.toString()
        } else if (value instanceof List) {
            return value.collect { serializeValue(it) }
        } else if (value instanceof Map) {
            return value.collectEntries { k, v -> [(k): serializeValue(v)] }
        }
        return value
    }

    /**
     * Generate environment variables for the notebook execution
     */
    Map<String, String> buildEnvironment() {
        def env = [:]

        env['NXF_TASK_NAME'] = task.processor.name
        env['NXF_TASK_INDEX'] = task.index.toString()
        env['NXF_TASK_ATTEMPT'] = (task.config.attempt ?: 1).toString()
        env['NXF_WORK_DIR'] = task.workDir.toString()
        env['NXF_PROJECT_DIR'] = task.processor.session.baseDir.toString()

        // Add custom environment variables from config
        def customEnv = config.env
        if (customEnv instanceof Map) {
            customEnv.each { key, value ->
                env[key.toString()] = value.toString()
            }
        }

        return env
    }

    /**
     * Write the inputs.json file to the task work directory
     */
    void writeInputsJson() {
        def inputsJson = buildInputsJson()
        def jsonContent = JsonOutput.prettyPrint(JsonOutput.toJson(inputsJson))
        def inputsFile = task.workDir.resolve('inputs.json')
        inputsFile.text = jsonContent
        log.trace "Generated inputs.json for ${task.name}: $jsonContent"
    }

    /**
     * Check if the notebook command is available
     */
    boolean isCommandAvailable(String command) {
        try {
            def process = ['which', command].execute()
            process.waitFor()
            return process.exitValue() == 0
        } catch (Exception e) {
            return false
        }
    }

    /**
     * Validate that required tools are available
     */
    void validate() {
        def type = detectType()

        switch (type) {
            case NotebookType.MARIMO:
                if (!isCommandAvailable('marimo')) {
                    throw new ProcessException(
                        "marimo command not found. Install with: pip install marimo\n" +
                        "Or ensure marimo is available in your container/conda environment."
                    )
                }
                break
            case NotebookType.JUPYTER:
                if (!isCommandAvailable('jupyter')) {
                    throw new ProcessException("jupyter command not found. Install with: pip install jupyter")
                }
                break
        }

        // Validate notebook file exists (relative to project directory)
        def notebookPath = config.notebook as String
        def projectDir = task.processor.session.baseDir
        def notebookFile = projectDir.resolve(notebookPath)

        if (!notebookFile.exists()) {
            throw new ProcessException("Notebook file not found: $notebookPath (looking in: $projectDir)")
        }
    }
}
```

---

### 4. TaskProcessor.groovy (MODIFICATIONS)
**File**: `/home/user/nextflow/modules/nextflow/src/main/groovy/nextflow/processor/TaskProcessor.groovy`

We need to find where task commands are built and add studios handling. This will require exploring the file more to find the right injection point.

**Search for**: Methods like `makeCommand()`, `createTaskHashKey()`, or similar that build task execution commands.

**Concept** (exact location TBD):
```groovy
// In the method that builds task command/script
protected String buildTaskScript() {
    def studios = task.config.getStudios()
    if (studios) {
        return buildStudiosScript(studios)
    }
    // ... existing script building logic
}

private String buildStudiosScript(Map studios) {
    def handler = new StudioHandler(studios, task)

    // Validate before execution
    handler.validate()

    // Write inputs.json
    handler.writeInputsJson()

    // Build command
    def command = handler.buildCommand()

    // Add environment setup
    def env = handler.buildEnvironment()
    def envVars = env.collect { k, v -> "export $k='$v'" }.join('\n')

    return """\
${envVars}

${command}
""".stripIndent()
}
```

**NOTE**: The exact integration point needs to be determined by exploring TaskProcessor.groovy and related task execution classes.

---

## Implementation Phases

### Phase 1: MVP (Minimal Viable Product)

**Goal**: Basic Marimo notebook execution with input/output passing

**Files to change**:
1. ✅ `ProcessConfig.groovy` - Add 'studios' to DIRECTIVES, add studios() methods
2. ✅ `TaskConfig.groovy` - Add getStudios() method
3. ✅ `StudioHandler.groovy` - Create new handler class
4. 🔍 `TaskProcessor.groovy` - Integration point (needs exploration)

**Testing**:
- Create simple test workflow with Marimo notebook
- Pass input files and parameters
- Verify outputs are generated

### Phase 2: Complete Marimo Support

**Goal**: Full-featured Marimo support with validation, error handling, HTML export

**Additional features**:
- HTML export option
- Better error messages
- Input validation
- Environment variable passing
- Container/conda integration

### Phase 3: Jupyter Support

**Goal**: Add Jupyter notebook support via Papermill

**Files**:
- Extend `StudioHandler` with Jupyter/Papermill support
- Handle `.ipynb` format
- Kernel management

### Phase 4: Seqera Studios Integration

**Goal**: Integration with Seqera Platform Data Studios

**Files**:
- Seqera API client
- Authentication handling
- Studio launching and monitoring

---

## Next Steps

### Immediate Actions Needed:

1. **Explore TaskProcessor.groovy** to find the correct integration point for script building
   - Look for: `makeCommand()`, `makeWrapper()`, `buildScript()` methods
   - Understand: Where process scripts are generated and executed

2. **Create test infrastructure**:
   - Create `tests/studios/` directory
   - Add sample Marimo notebook
   - Create test workflow

3. **Implement Phase 1 changes** in this order:
   ```
   a. ProcessConfig.groovy changes
   b. TaskConfig.groovy changes
   c. StudioHandler.groovy creation
   d. TaskProcessor.groovy integration
   e. Basic test
   ```

4. **Testing strategy**:
   ```groovy
   // tests/studios/test_marimo.nf
   #!/usr/bin/env nextflow

   process test_studios {
       studios notebook: 'test.py'

       input:
       val message

       output:
       path 'output.txt'

       script:
       """
       echo "Fallback: $message"
       """
   }

   workflow {
       Channel.of('Hello Studios') | test_studios
   }
   ```

---

## Questions to Resolve

1. **Script override**: Should `studios:` make the `script:` block optional?
   - Currently: Script block would be fallback
   - Alternative: Make script optional when studios is present

2. **Caching**: How should studios tasks be cached?
   - Hash notebook file content + inputs?
   - Option to disable caching?

3. **Containers**: How to ensure marimo is available?
   - User provides container with marimo installed?
   - Auto-install via conda directive?
   - Both options?

4. **Notebook staging**: Should we copy notebook to work dir?
   - Currently: Reference from project dir
   - Alternative: Stage to work dir like other files

5. **Output handling**:
   - Should we auto-export HTML by default?
   - Make it configurable?

---

## File Tree

```
nextflow/
├── modules/nextflow/src/
│   ├── main/groovy/nextflow/
│   │   ├── script/
│   │   │   └── ProcessConfig.groovy        [MODIFY]
│   │   └── processor/
│   │       ├── TaskConfig.groovy           [MODIFY]
│   │       ├── TaskProcessor.groovy        [MODIFY - TBD]
│   │       └── StudioHandler.groovy        [CREATE]
│   └── test/groovy/nextflow/
│       ├── script/
│       │   └── ProcessConfigTest.groovy    [MODIFY - Add tests]
│       └── processor/
│           └── StudioHandlerTest.groovy    [CREATE]
└── tests/
    └── studios/                             [CREATE]
        ├── test_marimo.nf
        ├── test.py
        └── README.md
```

---

## Resources

- **Design Doc**: `STUDIOS_DIRECTIVE_DESIGN.md`
- **Marimo Docs**: https://docs.marimo.io/
- **Snakemake Reference**: https://github.com/snakemake/snakemake/blob/main/src/snakemake/notebook.py
- **Seqera Studios API**: https://docs.seqera.io/platform-api/create-data-studio

---

## Success Criteria

### Phase 1 Complete When:
- [ ] Can define `studios notebook: 'file.py'` in a process
- [ ] Marimo notebook executes with Nextflow inputs available
- [ ] Output files are captured correctly
- [ ] Basic error handling works
- [ ] At least one integration test passes

### Phase 2 Complete When:
- [ ] HTML export option works
- [ ] Environment variables passed correctly
- [ ] Container integration tested
- [ ] Comprehensive test suite
- [ ] Documentation written

---

This implementation plan provides a clear roadmap from MVP to full feature set, with concrete file locations and code patterns to follow.
