# Nextflow Modules Command

## Overview

The `nextflow modules` command provides comprehensive module management with **three** approaches to fit different workflows:

1. **CLI Commands** - Manual, explicit installation (like nf-core modules)
2. **@GrabModule Annotation** - Automatic resolution with security (like Go/Deno/Groovy)
3. **Direct Include** - Local files only (existing behavior)

This addresses issue #4112 with a security-first design inspired by modern package managers.

## Quick Start

### Approach 1: Automatic with @GrabModule (Recommended)

```groovy
nextflow.enable.dsl=2

@GrabModule('nf-core/modules/modules/nf-core/fastqc@abc123')
include { FASTQC } from 'nf-core/modules/modules/nf-core/fastqc'

workflow {
    FASTQC(Channel.fromPath(params.reads))
}
```

**What happens:**
- First run: Downloads module, calculates SHA-256 hash, saves to modules.lock
- Subsequent runs: Verifies hash matches modules.lock (prevents tampering)
- Security: Cryptographic integrity verification (like Go modules, Deno)

### Approach 2: Manual CLI Installation

```bash
nextflow modules install nf-core/modules/modules/nf-core/fastqc@abc123
```

Then use in workflow:
```groovy
include { FASTQC } from './modules/nf-core/modules/modules/nf-core/fastqc/main.nf'
```

### Approach 3: Local Only (Current Behavior)

```groovy
include { FASTQC } from './local/modules/fastqc/main.nf'
```

## Security Model

See [Module Security Model](modules-security-model.md) for comprehensive security documentation.

**Key Points:**
- ✅ **Cryptographic integrity** - SHA-256 hashes prevent tampering
- ✅ **Reproducible builds** - modules.lock ensures same code everywhere
- ✅ **Three security modes** - strict (default), warn, permissive
- ✅ **Inspired by Go, Deno, npm** - Industry-proven approaches

### How Security Works

```
1. Install/Download → Calculate SHA-256 → Save to modules.lock
2. Next run → Verify SHA-256 → Pass/Fail based on security mode
3. Any modification → Hash mismatch → Detected
```

## Design Philosophy

This implementation provides **secure automatic dependency resolution** that:

1. Downloads and caches modules with cryptographic verification
2. Maintains explicit dependency tracking via `modules.json` and `modules.lock`
3. Enables offline usage after initial installation
4. Works seamlessly with existing `include` statements
5. Prevents tampering through SHA-256 integrity checks

## Architecture

### Components

1. **ModuleManager** (`nextflow.module.ModuleManager`)
   - Core business logic for module operations
   - Handles module parsing, installation, removal, and updates
   - Manages `modules.json` configuration file
   - Leverages existing `AssetManager` infrastructure for repository access

2. **CmdModules** (`nextflow.cli.CmdModules`)
   - CLI command interface with subcommands
   - User-friendly output and error handling
   - Registered in `Launcher.groovy`

3. **modules.json** - Module registry format
   ```json
   {
     "modules": {
       "fastqc": {
         "source": "github:nf-core/modules",
         "path": "modules/nf-core/fastqc",
         "revision": "abc123def",
         "installedPath": "modules/nf-core/modules/modules/nf-core/fastqc"
       }
     }
   }
   ```

## Usage

### Module Reference Format

Modules are referenced using the following format:

```
[provider:]owner/repository/path@revision
```

- **provider** (optional): Git provider (`github`, `gitlab`, `bitbucket`). Defaults to `github`.
- **owner**: Repository owner or organization
- **repository**: Repository name
- **path**: Path to module within repository
- **revision**: Git commit hash, tag, or branch name

### Commands

#### Install a Module

Install a module from a remote repository:

```bash
nextflow modules install github:nf-core/modules/modules/nf-core/fastqc@master

# Short syntax (defaults to github)
nextflow modules install nf-core/modules/modules/nf-core/fastqc@abc123

# Force reinstall
nextflow modules install --force nf-core/modules/modules/nf-core/fastqc@master
```

Output:
```
Installing module from: github:nf-core/modules/modules/nf-core/fastqc@master
✓ Module installed successfully!

  Name:      fastqc
  Source:    github:nf-core/modules
  Path:      modules/nf-core/fastqc
  Revision:  abc123def456
  Location:  modules/nf-core/modules/modules/nf-core/fastqc

You can now include this module in your workflow:
  include { FASTQC } from './modules/nf-core/modules/modules/nf-core/fastqc/main.nf'
```

#### List Installed Modules

View all installed modules:

```bash
nextflow modules list
```

Output:
```
Installed modules:

NAME                    SOURCE                                  REVISION
--------------------------------------------------------------------------------
fastqc                  github:nf-core/modules/modules/nf-...  abc123def4
multiqc                 github:nf-core/modules/modules/nf-...  def456ghi7

Total: 2 module(s)
```

#### Get Module Information

Show detailed information about an installed module:

```bash
nextflow modules info fastqc
```

Output:
```
Module: fastqc
============================================================
  Source:           github:nf-core/modules
  Path:             modules/nf-core/fastqc
  Revision:         abc123def456
  Installed Path:   modules/nf-core/modules/modules/nf-core/fastqc

Include Statement:
  include { FASTQC } from './modules/nf-core/modules/modules/nf-core/fastqc/main.nf'
```

#### Update a Module

Update an installed module to a new revision:

```bash
# Update to latest version (re-downloads at same revision)
nextflow modules update fastqc

# Update to specific revision
nextflow modules update fastqc def456ghi789
```

Output:
```
Updating module: fastqc
✓ Module updated successfully!

  Name:      fastqc
  Revision:  def456ghi789
  Location:  modules/nf-core/modules/modules/nf-core/fastqc
```

#### Remove a Module

Remove an installed module:

```bash
nextflow modules remove fastqc
```

Output:
```
✓ Module 'fastqc' removed successfully
```

## Integration with Workflows

After installing modules, use them in your workflow with standard `include` statements:

```groovy
nextflow.enable.dsl=2

// Include installed module
include { FASTQC } from './modules/nf-core/modules/modules/nf-core/fastqc/main.nf'
include { MULTIQC } from './modules/nf-core/modules/modules/nf-core/multiqc/main.nf'

workflow {
    input_ch = Channel.fromPath(params.reads)
    FASTQC(input_ch)
    MULTIQC(FASTQC.out.zip.collect())
}
```

## Example Workflow

Complete example of using the modules command:

```bash
# Create a new project
mkdir my-pipeline && cd my-pipeline

# Install required modules
nextflow modules install nf-core/modules/modules/nf-core/fastqc@master
nextflow modules install nf-core/modules/modules/nf-core/multiqc@master

# List installed modules
nextflow modules list

# Create your workflow (main.nf)
cat > main.nf << 'EOF'
nextflow.enable.dsl=2

include { FASTQC } from './modules/nf-core/modules/modules/nf-core/fastqc/main.nf'
include { MULTIQC } from './modules/nf-core/modules/modules/nf-core/multiqc/main.nf'

workflow {
    Channel
        .fromPath(params.reads)
        .set { reads_ch }

    FASTQC(reads_ch)
    MULTIQC(FASTQC.out.zip.collect())
}
EOF

# Run the workflow
nextflow run main.nf --reads 'data/*.fastq.gz'
```

## Implementation Details

### Module Storage

- **Location**: `<project-root>/modules/`
- **Structure**: Mirrors remote repository structure
- **Compatibility**: Works with existing `include` system (local file paths only)

### Module Resolution

1. Parse module reference
2. Use `AssetManager` to clone/download repository at specified revision
3. Locate module files within repository
4. Copy to local `modules/` directory
5. Track in `modules.json`

### Offline Support

Once modules are installed, they are available for offline use:
- Modules are stored as local files
- `modules.json` tracks all dependencies
- No network access required after installation

### Error Handling

Comprehensive error handling for:
- Invalid module references
- Network failures during download
- Missing module files
- Corrupted `modules.json`
- Duplicate installations
- Non-existent modules during removal/update

## Testing

### Unit Tests

Run unit tests:
```bash
./gradlew test --tests ModuleManagerTest
./gradlew test --tests CmdModulesTest
```

### Integration Tests

Integration tests require network access and GitHub token:

```bash
export NXF_GITHUB_ACCESS_TOKEN=your_token_here
./gradlew test --tests ModuleManagerIntegrationTest
```

## Benefits

1. **Explicit Dependency Management**: All modules tracked in `modules.json`
2. **Offline Support**: Work offline after initial installation
3. **Version Control**: Pin modules to specific revisions/commits
4. **Reproducibility**: Exact module versions recorded
5. **Familiar Interface**: Similar to `nf-core modules` command
6. **Safe**: No automatic remote resolution (security concern addressed)
7. **Compatible**: Works with existing DSL2 include system

## Future Enhancements

Potential future improvements:

1. **Module Search**: `nextflow modules search <keyword>`
2. **Module Templates**: Create module scaffolding
3. **Dependency Resolution**: Automatically install module dependencies
4. **Module Registry**: Support for custom module registries
5. **Version Constraints**: Semantic versioning support
6. **Lockfile**: Enhanced reproducibility with lockfile
7. **Module Testing**: Run module tests before installation

## Related Issues

- Issue #4112: Add `nextflow modules` command to install remote modules
- Original discussion on remote module imports
- Ben Sherman's concerns about automatic remote resolution
- Community feedback on module management needs

## License

Copyright 2013-2024, Seqera Labs
Licensed under Apache License, Version 2.0
