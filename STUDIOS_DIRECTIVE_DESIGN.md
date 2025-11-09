# Studios Directive Design for Nextflow

## Overview

The `studios:` directive enables Nextflow processes to execute interactive notebooks (starting with Marimo, expanding to Seqera Studios) as workflow steps, similar to Snakemake's notebook feature.

## Motivation

- **Reproducible data exploration**: Combine exploratory analysis with workflow automation
- **Interactive development**: Develop analysis steps interactively, then run in production
- **Documentation**: Notebooks serve as self-documenting analysis steps
- **Seqera Studios integration**: Future integration with Seqera Platform Data Studios

## Design Goals

1. **Simple initial implementation** with Marimo (pure Python, easy execution)
2. **Clean syntax** that feels native to Nextflow DSL
3. **Seamless parameter passing** from Nextflow to notebooks
4. **Extensible** for future notebook types (Jupyter, RMarkdown, Seqera Studios)
5. **Development mode** for interactive editing (like Snakemake's `--edit` flag)

---

## Proposed Syntax

### Option 1: Directive-based (Recommended)

```groovy
process analyzeData {
    input:
    path input_file
    val sample_id

    output:
    path "results.html"
    path "output.csv"

    studios:
    notebook: 'analysis.py'
    type: 'marimo'  // Optional, auto-detect from extension
    edit: false     // When true, opens notebook in interactive mode

    // Script block is optional when using studios
    script:
    """
    # Fallback if marimo not available
    python analysis_fallback.py
    """
}
```

### Option 2: Script-like syntax (Alternative)

```groovy
process analyzeData {
    input:
    path input_file
    val sample_id

    output:
    path "results.html"

    studios:
    """
    marimo:analysis.py
    """
}
```

### Option 3: Map-based syntax (Most flexible)

```groovy
process analyzeData {
    studios notebook: 'analysis.py', type: 'marimo', mode: 'run'

    input:
    path data

    output:
    path "*.html"
}
```

**Recommendation: Option 3** - Most Nextflow-idiomatic, similar to `publishDir`

---

## Directive Parameters

### Required
- **`notebook`** (String/Path): Path to notebook file (relative to project directory or absolute)

### Optional
- **`type`** (String): Notebook type - `'marimo'`, `'jupyter'`, `'seqera'` (default: auto-detect from extension)
- **`mode`** (String): Execution mode
  - `'run'` (default): Execute notebook non-interactively
  - `'edit'`: Open notebook for interactive development (development mode)
  - `'draft'`: Create skeleton notebook from process inputs/outputs
- **`kernel`** (String): For Jupyter notebooks - `'python'`, `'R'`, `'julia'` (default: auto-detect)
- **`output`** (String): Output format - `'html'`, `'notebook'`, `'both'` (default: `'notebook'`)
- **`env`** (Map): Additional environment variables to pass to notebook
- **`args`** (List/Map): Additional arguments to pass to notebook
- **`enabled`** (Boolean/Closure): Conditional execution (default: `true`)

---

## Implementation Details

### Phase 1: Marimo Support

#### Execution Flow

1. **Process Initialization**
   - Detect `studios` directive in ProcessConfig
   - Validate notebook file exists
   - Auto-detect notebook type from extension (`.py` → marimo)

2. **Task Preparation**
   - Stage input files to task work directory
   - Generate argument mapping file (JSON) with:
     - `input`: Map of input channel values
     - `output`: List of expected output file names
     - `params`: Process params
     - `task`: Task metadata (name, workDir, index, attempt)

3. **Notebook Execution** (mode: 'run')
   ```bash
   # Set environment variables
   export NXF_TASK_NAME="analyzeData"
   export NXF_TASK_INDEX="1"
   export NXF_WORK_DIR="/work/abc123"

   # Execute marimo notebook with parameters
   marimo run analysis.py -- \
     --nxf-inputs inputs.json \
     --input-file data.csv \
     --sample-id "sample_001" \
     --output-dir .
   ```

4. **Output Capture**
   - Collect output files specified in `output:` block
   - Optionally export HTML version: `marimo export html analysis.py -o results.html`
   - Stage outputs to publishDir or downstream processes

#### Interactive Mode (mode: 'edit')

When running with `nextflow run -with-studios edit` or `mode: 'edit'`:

```bash
# Start marimo in edit mode
marimo edit analysis.py
```

- Pauses workflow execution
- Opens notebook in browser for development
- User develops/tests interactively
- On save/exit, continues workflow in run mode

#### Notebook Template (Draft Mode)

Auto-generate marimo notebook template:

```python
import marimo

__generated_with__ = "0.0.1"
app = marimo.App()

@app.cell
def __():
    import marimo as mo
    import json
    return mo, json

@app.cell
def __(mo, json):
    # Nextflow parameters injected by studios directive
    args = mo.cli_args()

    # Load Nextflow inputs
    with open(args.get('nxf_inputs', 'inputs.json')) as f:
        nxf = json.load(f)

    input_file = args.get('input_file', nxf['input'].get('input_file'))
    sample_id = args.get('sample_id', nxf['params'].get('sample_id'))

    return input_file, sample_id, nxf

@app.cell
def __(input_file):
    # Your analysis code here
    import pandas as pd
    data = pd.read_csv(input_file)

    # Process data...
    results = data.describe()

    return data, results

@app.cell
def __(results, nxf):
    # Save outputs
    output_dir = nxf['task']['workDir']
    results.to_csv(f'{output_dir}/output.csv')

    return

if __name__ == "__main__":
    app.run()
```

### Nextflow Context Available to Notebooks

Passed via `inputs.json`:

```json
{
  "input": {
    "input_file": "/path/to/data.csv",
    "sample_id": "sample_001"
  },
  "output": {
    "expected": ["results.html", "output.csv"]
  },
  "params": {
    "alpha": 0.05,
    "iterations": 1000
  },
  "task": {
    "name": "analyzeData",
    "workDir": "/work/abc123",
    "index": 1,
    "attempt": 1,
    "process": "analyzeData"
  },
  "workflow": {
    "projectDir": "/home/user/project",
    "launchDir": "/home/user/project/results",
    "workDir": "/work"
  }
}
```

Environment variables:
```bash
NXF_TASK_NAME=analyzeData
NXF_TASK_INDEX=1
NXF_TASK_ATTEMPT=1
NXF_WORK_DIR=/work/abc123
NXF_PROJECT_DIR=/home/user/project
```

---

## Code Changes Required

### 1. ProcessConfig.groovy

```groovy
// Add to DIRECTIVES list (line ~46)
static final public List<String> DIRECTIVES = [
    // ... existing directives ...
    'studios',
]

// Add studios directive handler (around line 720+)
ProcessConfig studios(Map params) {
    if (!params.containsKey('notebook')) {
        throw new IllegalArgumentException("studios directive requires 'notebook' parameter")
    }
    configProperties.put('studios', params)
    return this
}

ProcessConfig studios(String notebookPath) {
    configProperties.put('studios', [notebook: notebookPath])
    return this
}
```

### 2. TaskConfig.groovy

```groovy
// Add getter method
Map getStudios() {
    def value = get('studios')
    if (value instanceof Map) {
        return value
    } else if (value instanceof CharSequence) {
        return [notebook: value.toString()]
    }
    return null
}
```

### 3. New Class: StudioHandler.groovy

Create `modules/nextflow/src/main/groovy/nextflow/processor/StudioHandler.groovy`:

```groovy
package nextflow.processor

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.exception.ProcessException

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
        this.config = config
        this.task = task
    }

    NotebookType detectType() {
        String type = config.type
        if (type) {
            return NotebookType.valueOf(type.toUpperCase())
        }

        // Auto-detect from extension
        String notebook = config.notebook
        if (notebook.endsWith('.py')) {
            return NotebookType.MARIMO
        } else if (notebook.endsWith('.ipynb')) {
            return NotebookType.JUPYTER
        } else if (notebook.endsWith('.Rmd')) {
            return NotebookType.RMARKDOWN
        }

        throw new ProcessException("Cannot detect notebook type for: $notebook")
    }

    String buildCommand() {
        def type = detectType()
        def mode = config.mode ?: 'run'

        switch (type) {
            case NotebookType.MARIMO:
                return buildMarimoCommand(mode)
            case NotebookType.JUPYTER:
                return buildJupyterCommand(mode)
            default:
                throw new ProcessException("Notebook type $type not yet implemented")
        }
    }

    private String buildMarimoCommand(String mode) {
        def notebook = config.notebook
        def args = buildNotebookArgs()

        if (mode == 'edit') {
            return "marimo edit ${notebook}"
        }

        return "marimo run ${notebook} -- ${args}"
    }

    private String buildNotebookArgs() {
        def args = []

        // Pass inputs as arguments
        task.config.getInputs().each { name, value ->
            args << "--${name} ${value}"
        }

        // Pass params
        args << "--nxf-inputs inputs.json"

        return args.join(' ')
    }

    Map buildInputsJson() {
        return [
            input: task.getInputs(),
            output: task.getOutputs(),
            params: task.config.getParams(),
            task: [
                name: task.name,
                workDir: task.workDir,
                index: task.index,
                attempt: task.attempt
            ],
            workflow: [
                projectDir: task.processor.session.projectDir,
                launchDir: task.processor.session.launchDir,
                workDir: task.processor.session.workDir
            ]
        ]
    }
}
```

### 4. TaskProcessor.groovy

Modify task execution to check for studios directive:

```groovy
// In createTaskHashKey or makeCommand method
protected String makeCommand() {
    def studios = task.config.getStudios()
    if (studios) {
        // Use StudioHandler
        def handler = new StudioHandler(studios, task)

        // Write inputs.json
        def inputsJson = handler.buildInputsJson()
        task.workDir.resolve('inputs.json').text =
            groovy.json.JsonOutput.toJson(inputsJson)

        // Build command
        return handler.buildCommand()
    }

    // Fallback to regular script
    return super.makeCommand()
}
```

---

## Usage Examples

### Example 1: Simple Marimo Notebook

```groovy
#!/usr/bin/env nextflow

params.input_csv = 'data.csv'
params.alpha = 0.05

process exploratory_analysis {
    publishDir 'results', mode: 'copy'

    input:
    path data_file

    output:
    path "analysis.html"
    path "figures/*.png"

    studios notebook: 'notebooks/explore.py'
}

workflow {
    Channel.fromPath(params.input_csv) | exploratory_analysis
}
```

### Example 2: Multiple Outputs with Parameters

```groovy
process statistical_test {
    input:
    tuple val(sample_id), path(counts)
    val alpha

    output:
    tuple val(sample_id), path("${sample_id}_results.csv")
    path "${sample_id}_plots.html"

    studios notebook: 'stats/test.py',
            type: 'marimo',
            output: 'both'  // Save notebook + export HTML
}

workflow {
    samples = Channel.fromFilePairs('data/*_counts.csv')
    statistical_test(samples, params.alpha)
}
```

### Example 3: Conditional Execution

```groovy
process visualize {
    studios notebook: 'viz/plots.py',
            enabled: { params.create_plots }

    when:
    params.create_plots

    input:
    path results

    output:
    path "*.png"
}
```

### Example 4: Development Mode

```bash
# Run workflow with interactive notebook editing
nextflow run main.nf -with-studios edit

# Or set in config
nextflow.config:
studios {
    mode = 'edit'  // Global setting
}
```

---

## Future Extensions

### Phase 2: Jupyter Notebook Support

```groovy
studios notebook: 'analysis.ipynb',
        type: 'jupyter',
        kernel: 'python3'
```

Execution via papermill:
```bash
papermill input.ipynb output.ipynb \
  -p input_file data.csv \
  -p sample_id sample_001
```

### Phase 3: Seqera Studios Integration

```groovy
studios notebook: 'seqera://my-org/analysis-studio',
        type: 'seqera',
        credentials: 'TOWER_ACCESS_TOKEN'
```

Integration with Seqera Platform:
- Launch Data Studio session via API
- Pass Nextflow inputs as Studio parameters
- Monitor execution status
- Retrieve outputs from Studio workspace

API call:
```groovy
POST https://api.cloud.seqera.io/data-studios
{
  "name": "nextflow-task-analysis",
  "templateId": "analysis-studio",
  "parameters": {
    "input_file": "s3://bucket/data.csv"
  }
}
```

### Phase 4: R Markdown Support

```groovy
studios notebook: 'report.Rmd',
        type: 'rmarkdown',
        output_format: 'html_document'
```

---

## Configuration

### Global Configuration (nextflow.config)

```groovy
studios {
    enabled = true
    mode = 'run'  // 'run', 'edit', 'draft'

    marimo {
        version = '0.10.0'
        pythonPath = '/usr/bin/python3'
    }

    jupyter {
        kernel = 'python3'
        papermill = true
    }

    seqera {
        endpoint = 'https://api.cloud.seqera.io'
        workspace = 'my-org/my-workspace'
    }
}
```

### Process-level Override

```groovy
process myProcess {
    studios notebook: 'nb.py', mode: params.interactive ? 'edit' : 'run'
}
```

---

## Testing Strategy

### Unit Tests

- `ProcessConfigTest.groovy`: Test studios directive parsing
- `TaskConfigTest.groovy`: Test studios configuration access
- `StudioHandlerTest.groovy`: Test command building

### Integration Tests

- Create test workflows with marimo notebooks
- Verify input/output passing
- Test error handling
- Verify HTML export

### Test Notebooks

Create `tests/notebooks/`:
- `simple.py`: Basic marimo notebook with inputs/outputs
- `params.py`: Notebook using parameters
- `multi_output.py`: Multiple output files

---

## Error Handling

### Validation Errors

```groovy
// No notebook specified
studios {}
// Error: studios directive requires 'notebook' parameter

// Notebook file doesn't exist
studios notebook: 'missing.py'
// Error: Notebook file not found: missing.py

// Unsupported type
studios notebook: 'file.xyz'
// Error: Cannot detect notebook type for: file.xyz
```

### Runtime Errors

```groovy
// Marimo not installed
// Error: marimo command not found. Install with: pip install marimo

// Notebook execution failed
// Error: Notebook execution failed with exit code 1
// Show notebook error output

// Output files missing
// Error: Expected output file not created: results.csv
```

---

## Documentation

### User Guide Sections

1. **Introduction to Studios Directive**
2. **Getting Started with Marimo Notebooks**
3. **Passing Parameters from Nextflow**
4. **Interactive Development Mode**
5. **Publishing Notebook Outputs**
6. **Seqera Studios Integration** (Phase 3)

### API Reference

- `studios` directive parameters
- Nextflow context JSON structure
- Environment variables
- Configuration options

---

## Backward Compatibility

- No breaking changes to existing directives
- Optional directive - workflows without `studios` work unchanged
- Graceful fallback if notebook runtime not available (use `script:` block)

---

## Open Questions

1. **Caching**: How should notebook tasks be cached?
   - Hash notebook file content + inputs?
   - Option to disable caching for exploratory notebooks?

2. **Containers**: How to handle marimo/jupyter in containers?
   - Require marimo in container image?
   - Auto-install via conda/pip if not present?

3. **Output validation**: Should we validate expected outputs?
   - Warn if output files not created?
   - Fail task or just warn?

4. **Notebook versioning**: Track notebook format version?
   - Metadata in notebook about Nextflow compatibility?

5. **Resource management**: Should notebooks inherit process resources?
   - cpus, memory available to notebook?
   - Pass as environment variables?

---

## Implementation Priority

### MVP (Minimum Viable Product)
1. ✅ Design specification (this document)
2. Add `studios` to DIRECTIVES list
3. Implement StudioHandler for Marimo
4. Basic input/output passing via CLI args
5. Simple execution in 'run' mode
6. Basic tests

### Phase 1 (Full Marimo Support)
1. Inputs JSON generation
2. Environment variables
3. HTML export
4. Error handling
5. Comprehensive tests
6. Documentation

### Phase 2 (Jupyter Support)
1. Papermill integration
2. Kernel management
3. ipynb parsing

### Phase 3 (Seqera Studios)
1. API client
2. Authentication
3. Studio launching
4. Output retrieval

---

## Summary

This design provides:
- **Simple starting point** with Marimo (pure Python, easy execution)
- **Clean Nextflow-idiomatic syntax** using Map-based directive
- **Comprehensive parameter passing** via JSON + CLI args
- **Development mode** for interactive editing
- **Extensible architecture** for Jupyter, RMarkdown, Seqera Studios
- **Clear implementation path** with phased rollout

The key advantage of starting with Marimo:
- No JSON parsing (pure Python)
- No kernel management
- Simple subprocess execution
- Git-friendly
- Modern, reactive notebook design

This creates a solid foundation for future notebook types while delivering immediate value.
