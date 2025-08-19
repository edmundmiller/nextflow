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

package nextflow.cli

import groovy.transform.CompileDynamic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Adapter to bridge gradle plugin CommandExtensionPoint interface to main Nextflow CommandExtensionPoint.
 * 
 * This allows standalone plugins compiled against the gradle plugin interface to work
 * with the main Nextflow runtime which uses the main CommandExtensionPoint interface.
 *
 * @author Edmund Miller <edmund@seqera.io>
 */
@CompileDynamic
class GradlePluginCommandAdapter implements CommandExtensionPoint {

    private static final Logger log = LoggerFactory.getLogger(GradlePluginCommandAdapter.class)

    private final Object gradleExtension

    GradlePluginCommandAdapter(Object gradleExtension) {
        this.gradleExtension = gradleExtension
    }

    @Override
    String getCommandName() {
        return gradleExtension.getCommandName()
    }

    @Override
    String getCommandDescription() {
        return gradleExtension.getCommandDescription()
    }

    @Override
    int getPriority() {
        return gradleExtension.getPriority()
    }

    @Override
    CmdBase createCommand() {
        // The gradle plugin interface returns Object, we need to cast to CmdBase
        final command = gradleExtension.createCommand()
        if (command instanceof CmdBase) {
            return command as CmdBase
        } else {
            log.error("Gradle plugin command '${getCommandName()}' returned ${command.class.name} instead of CmdBase")
            throw new IllegalStateException("Plugin command must return a CmdBase instance")
        }
    }
}