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

import java.nio.file.Paths

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation

/**
 * AST transformation for @GrabModule annotation
 *
 * Performs automatic module resolution at compile time:
 * 1. Detects @GrabModule annotations
 * 2. Parses module references
 * 3. Downloads modules if not cached
 * 4. Verifies integrity against lockfile
 * 5. Updates module paths for include statements
 *
 * This runs during the SEMANTIC_ANALYSIS phase, after imports are resolved
 * but before script execution, similar to how @Grab works in Groovy.
 *
 * @author Edmund Miller <edmund.a.miller@gmail.com>
 */
@Slf4j
@CompileStatic
@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
class GrabModuleTransform implements ASTTransformation {

    @Override
    void visit(ASTNode[] nodes, SourceUnit source) {
        if (nodes.length != 2) return
        if (!(nodes[0] instanceof AnnotationNode)) return
        if (!(nodes[1] instanceof ClassNode)) return

        AnnotationNode annotationNode = (AnnotationNode) nodes[0]
        ClassNode classNode = (ClassNode) nodes[1]

        try {
            processGrabModuleAnnotation(annotationNode, classNode, source)
        } catch (Exception e) {
            log.error("Failed to process @GrabModule annotation", e)
            source.addError(new org.codehaus.groovy.control.messages.SyntaxErrorMessage(
                new org.codehaus.groovy.syntax.SyntaxException(
                    "Failed to grab module: ${e.message}",
                    annotationNode.lineNumber,
                    annotationNode.columnNumber
                ), source
            ))
        }
    }

    private void processGrabModuleAnnotation(AnnotationNode annotation, ClassNode classNode, SourceUnit source) {
        // Extract annotation parameters
        def valueExpr = annotation.getMember('value')
        if (!(valueExpr instanceof ConstantExpression)) {
            log.warn "Invalid @GrabModule value expression"
            return
        }

        def moduleRef = ((ConstantExpression) valueExpr).value as String
        def force = getAnnotationBooleanMember(annotation, 'force', false)
        def skipIntegrityCheck = getAnnotationBooleanMember(annotation, 'skipIntegrityCheck', false)

        log.info "Processing @GrabModule('${moduleRef}') [force=${force}, skipIntegrity=${skipIntegrityCheck}]"

        // Get project root (current working directory)
        def projectRoot = Paths.get(System.getProperty('user.dir'))

        // Initialize managers
        def moduleManager = new ModuleManager(projectRoot)
        def lockfile = new ModuleLockfile(projectRoot)

        // Parse module reference
        def ref = ModuleManager.ModuleReference.parse(moduleRef)

        // Check if already installed and locked
        if (!force && moduleManager.isInstalled(ref.moduleName) && lockfile.hasEntry(ref.moduleName)) {
            log.debug "Module ${ref.moduleName} already installed"

            // Verify integrity unless skipped
            if (!skipIntegrityCheck) {
                def installed = moduleManager.info(ref.moduleName)
                def verified = lockfile.verifyIntegrity(ref.moduleName, installed.installedPath)

                if (!verified) {
                    def securityMode = getSecurityMode()
                    handleIntegrityFailure(ref.moduleName, securityMode, source, annotation)
                }
            }
        } else {
            // Install module
            log.info "Auto-installing module: ${moduleRef}"
            def installed = moduleManager.install(moduleRef, force)

            // Add to lockfile with integrity hash
            lockfile.addEntry(
                ref.moduleName,
                installed.source,
                installed.path,
                installed.revision,
                installed.installedPath
            )

            log.info "Module ${ref.moduleName} installed and locked"
        }
    }

    private boolean getAnnotationBooleanMember(AnnotationNode annotation, String name, boolean defaultValue) {
        def member = annotation.getMember(name)
        if (member instanceof ConstantExpression) {
            def value = ((ConstantExpression) member).value
            if (value instanceof Boolean) {
                return value
            }
        }
        return defaultValue
    }

    private String getSecurityMode() {
        // Try to read from Nextflow config
        // For now, default to 'strict'
        // TODO: Read from session config when available
        return System.getProperty('nextflow.modules.security', 'strict')
    }

    private void handleIntegrityFailure(String moduleName, String securityMode, SourceUnit source, AnnotationNode annotation) {
        def message = "Module integrity verification failed for '${moduleName}'. " +
                     "Files may have been modified. " +
                     "Run 'nextflow modules update ${moduleName}' to fix."

        switch (securityMode) {
            case 'strict':
                source.addError(new org.codehaus.groovy.control.messages.SyntaxErrorMessage(
                    new org.codehaus.groovy.syntax.SyntaxException(
                        message,
                        annotation.lineNumber,
                        annotation.columnNumber
                    ), source
                ))
                break

            case 'warn':
                log.warn message
                break

            case 'permissive':
                log.debug message
                break

            default:
                log.warn "Unknown security mode '${securityMode}', using 'strict'"
                source.addError(new org.codehaus.groovy.control.messages.SyntaxErrorMessage(
                    new org.codehaus.groovy.syntax.SyntaxException(
                        message,
                        annotation.lineNumber,
                        annotation.columnNumber
                    ), source
                ))
        }
    }
}
