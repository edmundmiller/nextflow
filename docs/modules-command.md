# Nextflow Remote Modules

## Overview

Nextflow supports remote module imports directly in `include` statements, using the same infrastructure that powers remote pipeline execution:

```groovy
include { BOWTIE_ALIGN } from "github:nf-core/modules/modules/bowtie/align@abc123"
```

**Just like:**
```bash
nextflow run github:nextflow-io/hello
```

**Same pattern, same simplicity.**

## Quick Start

```groovy
nextflow.enable.dsl=2

// Remote modules - auto-downloaded and verified
include { FASTQC } from "github:nf-core/modules/modules/nf-core/fastqc@abc123"

workflow {
    FASTQC(Channel.fromPath(params.reads))
}
```

Run it:
```bash
nextflow run main.nf
```

Done! See [Remote Module Includes Guide](modules-remote-includes.md) for full documentation.
