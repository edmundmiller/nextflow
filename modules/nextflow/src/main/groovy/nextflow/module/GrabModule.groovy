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

package nextflow.module

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

import org.codehaus.groovy.transform.GroovyASTTransformationClass

/**
 * Annotation to automatically download and resolve remote Nextflow modules
 *
 * Similar to Groovy's @Grab annotation, but designed for Nextflow modules with:
 * - Cryptographic integrity verification (SHA-256)
 * - Lockfile-based reproducibility
 * - Automatic caching
 * - Security modes
 *
 * Examples:
 * <pre>
 * // Basic usage - downloads on first use
 * {@literal @}GrabModule('nf-core/modules/modules/nf-core/fastqc@master')
 * include { FASTQC } from 'nf-core/modules/modules/nf-core/fastqc'
 *
 * // With provider specification
 * {@literal @}GrabModule('github:nf-core/modules/modules/nf-core/fastqc@abc123')
 * include { FASTQC } from 'nf-core/modules/modules/nf-core/fastqc'
 *
 * // Multiple modules
 * {@literal @}GrabModule('nf-core/modules/modules/nf-core/fastqc@master')
 * {@literal @}GrabModule('nf-core/modules/modules/nf-core/multiqc@master')
 * include { FASTQC } from 'nf-core/modules/modules/nf-core/fastqc'
 * include { MULTIQC } from 'nf-core/modules/modules/nf-core/multiqc'
 * </pre>
 *
 * Security Model:
 * - First download: Module is fetched, hash computed, saved to modules.lock
 * - Subsequent runs: Integrity verified against modules.lock
 * - Modified files: Warning or error based on security mode
 *
 * Security Modes (via config):
 * - strict: Fail on integrity mismatch (default)
 * - warn: Warn but continue on mismatch
 * - permissive: No verification (not recommended for production)
 *
 * Configuration:
 * <pre>
 * modules {
 *     autoGrab = true  // Enable automatic module resolution (default: true)
 *     security = 'strict'  // strict|warn|permissive (default: strict)
 *     verifyIntegrity = true  // Verify checksums (default: true)
 * }
 * </pre>
 *
 * Comparison with other systems:
 * - Go modules: Similar to go.mod + go.sum with checksums
 * - Deno: Like remote imports with deno.lock for integrity
 * - Groovy @Grab: Automatic dependency resolution with Maven/Ivy
 *
 * @author Edmund Miller <edmund.a.miller@gmail.com>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.LOCAL_VARIABLE])
@GroovyASTTransformationClass(classes = [GrabModuleTransform])
@interface GrabModule {
    /**
     * Module reference in format: [provider:]owner/repo/path@revision
     *
     * Examples:
     * - 'nf-core/modules/modules/nf-core/fastqc@master'
     * - 'github:nf-core/modules/modules/nf-core/fastqc@abc123'
     * - 'gitlab:myorg/myrepo/modules/align@v1.0'
     */
    String value()

    /**
     * Force re-download even if already installed
     * Default: false
     */
    boolean force() default false

    /**
     * Skip integrity verification for this module
     * Default: false (verification enabled)
     */
    boolean skipIntegrityCheck() default false
}
