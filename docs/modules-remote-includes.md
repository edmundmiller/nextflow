# Remote Module Includes

## Overview

Nextflow now supports remote module imports directly in `include` statements, just like pipeline imports:

```groovy
// Remote modules - auto-download and verify
include { BOWTIE_ALIGN } from "github:nf-core/modules/modules/bowtie/align@abc123"
include { FASTQC } from "github:nf-core/modules/modules/nf-core/fastqc@def456"

// Local modules - existing behavior
include { CUSTOM } from "./local/modules/custom.nf"
```

**That's it!** No CLI commands, no annotations, just the familiar `include` statement enhanced to support remote URLs.

## Why This Design?

### Consistent with Pipelines

Nextflow already supports remote pipeline execution:

```bash
# This works:
nextflow run github:nextflow-io/hello

# So why not:
include { MODULE } from "github:nf-core/modules/path@hash"
```

**Same infrastructure, same user experience.**

### Simple Mental Model

**Two types of includes:**

1. **Remote** - `github:`, `gitlab:`, `bitbucket:`
2. **Local** - `./`, `../`, `/`

That's all you need to know.

## Syntax

### Remote Module Format

```
[provider:]owner/repository/path@revision
```

Components:
- **provider** (optional): `github`, `gitlab`, `bitbucket` (default: `github`)
- **owner**: Repository owner/organization
- **repository**: Repository name
- **path**: Path to module directory within repository
- **revision**: Git commit hash, tag, or branch

### Examples

```groovy
nextflow.enable.dsl=2

// GitHub (short form - provider defaults to github)
include { FASTQC } from "github:nf-core/modules/modules/nf-core/fastqc@master"

// GitLab
include { ALIGN } from "gitlab:myorg/modules/alignment/bowtie@v1.0.0"

// Bitbucket
include { PROCESS } from "bitbucket:company/modules/process@abc123"

// Specific commit hash (recommended for production)
include { MODULE } from "github:org/repo/path@1234567890abcdef"

workflow {
    FASTQC(Channel.fromPath(params.reads))
}
```

## Security Model

### Automatic Integrity Verification

Remote modules use **cryptographic integrity checking** inspired by Go modules and Deno:

#### First Run:
```
1. Download module from repository
2. Calculate SHA-256 hash of all files
3. Save to modules.lock
4. Module ready to use
```

#### Subsequent Runs:
```
1. Load expected hash from modules.lock
2. Calculate current hash of module files
3. Compare hashes
4. If match → Continue
5. If mismatch → Handle based on security mode
```

### modules.lock Format

```json
{
  "version": 1,
  "generated": "2024-01-15T10:30:00Z",
  "modules": {
    "fastqc": {
      "source": "github:nf-core/modules",
      "path": "modules/nf-core/fastqc",
      "revision": "abc123def456",
      "integrity": "sha256:a1b2c3d4e5f6...",
      "timestamp": 1705314600000,
      "files": {
        "main.nf": "sha256:1234567890...",
        "meta.yml": "sha256:abcdef1234..."
      }
    }
  }
}
```

**Commit this file to version control for reproducible builds!**

### Security Modes

Configure via `nextflow.config`:

```groovy
modules {
    security = 'strict'  // strict|warn|permissive (default: strict)
}
```

| Mode | Behavior | Use Case |
|------|----------|----------|
| **strict** | Fail on integrity mismatch | Production, CI/CD |
| **warn** | Warn but continue | Development |
| **permissive** | Silent, no verification | Emergency only (NOT recommended) |

## Complete Example

### main.nf

```groovy
nextflow.enable.dsl=2

// Remote modules with pinned revisions
include { FASTQC } from "github:nf-core/modules/modules/nf-core/fastqc@1234567"
include { MULTIQC } from "github:nf-core/modules/modules/nf-core/multiqc@abcdefg"

// Local custom module
include { CUSTOM_PROCESS } from "./modules/custom/main.nf"

workflow {
    // Input channel
    reads_ch = Channel.fromPath(params.reads)

    // Run FastQC
    FASTQC(reads_ch)

    // Collect and run MultiQC
    MULTIQC(FASTQC.out.zip.collect())

    // Custom processing
    CUSTOM_PROCESS(FASTQC.out.html)
}
```

### nextflow.config

```groovy
modules {
    security = 'strict'  // Fail on integrity mismatch
}

params {
    reads = 'data/*.fastq.gz'
}
```

### First Run

```bash
nextflow run main.nf
```

Output:
```
N E X T F L O W  ~  version 24.x.x

Resolving remote module: github:nf-core/modules/modules/nf-core/fastqc@1234567
Downloading repository github:nf-core/modules@1234567
Module fastqc locked with integrity hash

Resolving remote module: github:nf-core/modules/modules/nf-core/multiqc@abcdefg
Downloading repository github:nf-core/modules@abcdefg
Module multiqc locked with integrity hash

Launching workflow...
[OK] Process FASTQC completed
[OK] Process MULTIQC completed
```

**Files created:**
- `modules.lock` - Integrity hashes (commit to git!)
- `.nextflow/assets/github/nf-core/modules/` - Downloaded repository cache

### Subsequent Runs

```bash
nextflow run main.nf
```

Output:
```
N E X T F L O W  ~  version 24.x.x

Verifying module integrity: fastqc ✓
Verifying module integrity: multiqc ✓

Launching workflow...
[OK] Process FASTQC completed
[OK] Process MULTIQC completed
```

**Much faster** - no downloads, just verification!

## Best Practices

### 1. Pin to Commit Hashes

```groovy
// ❌ BAD: Branch can change
include { MODULE } from "github:org/repo/path@main"

// ✅ GOOD: Commit is immutable
include { MODULE } from "github:org/repo/path@1234567890abcdef"
```

### 2. Commit modules.lock

```bash
git add modules.lock
git commit -m "Lock module dependencies"
```

Ensures everyone uses the same module versions.

### 3. Review Lockfile Changes

```bash
git diff modules.lock
```

See exactly what changed when updating modules.

### 4. Use Strict Mode in Production

```groovy
modules {
    security = 'strict'
}
```

Prevents execution of tampered code.

### 5. CI/CD Verification

```yaml
# .github/workflows/ci.yml
- name: Run workflow (verifies integrity)
  run: nextflow run main.nf
```

Automatic integrity checks in CI/CD.

## Offline/Air-Gapped Environments

For environments without internet access:

```bash
# On internet-connected machine:
nextflow run main.nf  # Downloads modules, creates modules.lock

# Commit everything:
git add modules.lock .nextflow/assets/
git commit -m "Bundle modules for offline use"

# On air-gapped machine:
git pull
nextflow run main.nf  # Uses cached modules, no internet needed
```

## Comparison with Other Systems

| System | Syntax | Lockfile | Hash | Verification |
|--------|--------|----------|------|--------------|
| **Go** | `import "github.com/..."` | go.sum | SHA-256 | Automatic |
| **Deno** | `import "https://..."` | deno.lock | SHA-256 | `--lock` flag |
| **npm** | `require("package")` | package-lock.json | SHA-512 | Automatic |
| **Nextflow** | `include from "github:..."` | modules.lock | SHA-256 | Configurable |

## Troubleshooting

### Integrity Check Failed

```
Module integrity verification FAILED for 'fastqc'
```

**Cause:** Module files were modified locally

**Fix:**
1. Delete cached module: `rm -rf .nextflow/assets/github/nf-core/modules`
2. Re-run: `nextflow run main.nf` (re-downloads)

Or temporarily use warn mode:
```groovy
modules {
    security = 'warn'
}
```

### Module Not Found

```
Module file not found: /path/to/module/main.nf
```

**Cause:** Module path incorrect or module doesn't exist

**Fix:** Verify module path in repository

### Network Issues

```
Failed to download repository
```

**Cause:** No internet access or repository doesn't exist

**Fix:**
- Check internet connection
- Verify repository exists
- Use cached modules (if previously downloaded)

## Migration Guide

### From Local Modules

**Before:**
```bash
# Manual download
git clone https://github.com/nf-core/modules modules/nf-core
```

```groovy
include { FASTQC } from "./modules/nf-core/modules/nf-core/fastqc/main.nf"
```

**After:**
```groovy
include { FASTQC } from "github:nf-core/modules/modules/nf-core/fastqc@abc123"
```

**Benefits:**
- ✅ No manual download step
- ✅ Automatic updates (change revision)
- ✅ Integrity verification
- ✅ Version control via modules.lock

### From nf-core modules Command

**Before:**
```bash
nf-core modules install fastqc
```

```groovy
include { FASTQC } from "./modules/nf-core/fastqc/main.nf"
```

**After:**
```groovy
include { FASTQC } from "github:nf-core/modules/modules/nf-core/fastqc@abc123"
```

**Benefits:**
- ✅ No separate tool needed
- ✅ Self-contained in workflow
- ✅ Built-in integrity verification

## FAQ

### Q: Do I need to install modules manually?

**A:** No! Just use `include` with a remote URL and Nextflow handles everything.

### Q: Where are modules downloaded?

**A:** Cached in `.nextflow/assets/[provider]/[owner]/[repo]/`

### Q: Can I use private repositories?

**A:** Yes! Configure credentials in `~/.nextflow/scm`:

```properties
providers {
    github {
        user = 'username'
        password = 'token'
    }
}
```

### Q: What if I modify a module?

**A:**
1. Fork the repository
2. Make changes in your fork
3. Reference your fork: `include from "github:myorg/modules/path@mybranch"`

### Q: Do I need internet access every time?

**A:** No! After first download, modules are cached. Internet only needed for:
- First download
- Updating to new revision

### Q: How do I update a module?

**A:** Change the revision in the include statement:

```groovy
// Before
include { MODULE } from "github:org/repo/path@old123"

// After
include { MODULE } from "github:org/repo/path@new456"
```

Run workflow → New version downloaded → modules.lock updated

### Q: Is this secure?

**A:** Yes! Security features:
- ✅ HTTPS-only downloads
- ✅ SHA-256 integrity verification
- ✅ Tamper detection
- ✅ Reproducible via lockfile
- ✅ Same security model as Go modules and Deno

## Summary

**Remote module includes** provide:

1. ✅ **Simplicity** - Same syntax as pipeline imports
2. ✅ **Security** - Cryptographic integrity verification
3. ✅ **Reproducibility** - Lockfile guarantees
4. ✅ **Convenience** - Automatic downloading
5. ✅ **Offline Support** - Caching after first download
6. ✅ **Familiarity** - Uses existing `include` statement

**No new commands. No annotations. Just enhance what you already know.**

```groovy
include { BOWTIE_ALIGN } from "github:nf-core/modules/modules/bowtie/align@abc123"
```

**That's all you need!** 🎉
