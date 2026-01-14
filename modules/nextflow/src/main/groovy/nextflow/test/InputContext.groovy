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

/**
 * Context for capturing process/workflow inputs in test DSL
 *
 * Supports both named and indexed inputs:
 *   input["reads"] = Channel.of(file("test.fastq"))
 *   input[0] = Channel.of("hello")
 *
 * @author Edmund Miller
 */
@Slf4j
@CompileStatic
class InputContext {

    /** Named inputs map */
    private Map<String, Object> namedInputs = [:]

    /** Indexed inputs list */
    private List<Object> indexedInputs = []

    /**
     * Set input by name: input["reads"] = ...
     */
    void putAt(String name, Object value) {
        log.debug "Setting named input '${name}' = ${value?.class?.simpleName}"
        namedInputs[name] = value
    }

    /**
     * Get input by name: input["reads"]
     */
    Object getAt(String name) {
        return namedInputs[name]
    }

    /**
     * Set input by index: input[0] = ...
     */
    void putAt(int index, Object value) {
        log.debug "Setting indexed input [${index}] = ${value?.class?.simpleName}"
        // Expand list if necessary
        while (indexedInputs.size() <= index) {
            indexedInputs.add(null)
        }
        indexedInputs[index] = value
    }

    /**
     * Get input by index: input[0]
     */
    Object getAt(int index) {
        return index < indexedInputs.size() ? indexedInputs[index] : null
    }

    /**
     * Check if any named inputs were specified
     */
    boolean hasNamedInputs() {
        return !namedInputs.isEmpty()
    }

    /**
     * Check if any indexed inputs were specified
     */
    boolean hasIndexedInputs() {
        return !indexedInputs.isEmpty()
    }

    /**
     * Get all named inputs
     */
    Map<String, Object> getNamedInputs() {
        return Collections.unmodifiableMap(namedInputs)
    }

    /**
     * Get all indexed inputs as array for process invocation
     */
    Object[] getIndexedInputsArray() {
        return indexedInputs.toArray()
    }

    /**
     * Get inputs as array - prefers indexed if available, otherwise converts named
     */
    Object[] toArray() {
        if (hasIndexedInputs()) {
            return indexedInputsArray
        }
        // If only named inputs, return values in order
        return namedInputs.values().toArray()
    }

    /**
     * Clear all inputs
     */
    void clear() {
        namedInputs.clear()
        indexedInputs.clear()
    }

    @Override
    String toString() {
        def parts = []
        if (hasNamedInputs()) {
            parts << "named=${namedInputs.keySet()}"
        }
        if (hasIndexedInputs()) {
            parts << "indexed=${indexedInputs.size()}"
        }
        return "InputContext(${parts.join(', ')})"
    }
}
