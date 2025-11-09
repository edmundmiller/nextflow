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

import spock.lang.Specification

/**
 * Test suite for CmdModules
 *
 * @author Edmund Miller <edmund.a.miller@gmail.com>
 */
class CmdModulesTest extends Specification {

    def 'should get command name'() {
        given:
        def cmd = new CmdModules()

        expect:
        cmd.name == 'modules'
    }

    def 'should display help when no args provided'() {
        given:
        def cmd = new CmdModules()

        when:
        cmd.run()

        then:
        noExceptionThrown()
    }

    def 'should display help when help flag provided'() {
        given:
        def cmd = new CmdModules(help: true)

        when:
        cmd.run()

        then:
        noExceptionThrown()
    }

    def 'should create install subcommand'() {
        given:
        def args = ['nf-core/modules/path@main']

        when:
        def installCmd = new CmdModules.InstallCmd(args)

        then:
        installCmd.args == args
        !installCmd.force
    }

    def 'should create install subcommand with force flag'() {
        given:
        def args = ['--force', 'nf-core/modules/path@main']

        when:
        def installCmd = new CmdModules.InstallCmd(args)

        then:
        installCmd.args == ['nf-core/modules/path@main']
        installCmd.force
    }

    def 'should create install subcommand with -f flag'() {
        given:
        def args = ['-f', 'nf-core/modules/path@main']

        when:
        def installCmd = new CmdModules.InstallCmd(args)

        then:
        installCmd.args == ['nf-core/modules/path@main']
        installCmd.force
    }

    def 'should create list subcommand'() {
        given:
        def args = []

        when:
        def listCmd = new CmdModules.ListCmd(args)

        then:
        listCmd.args == []
    }

    def 'should create info subcommand'() {
        given:
        def args = ['modulename']

        when:
        def infoCmd = new CmdModules.InfoCmd(args)

        then:
        infoCmd.args == args
    }

    def 'should create update subcommand'() {
        given:
        def args = ['modulename']

        when:
        def updateCmd = new CmdModules.UpdateCmd(args)

        then:
        updateCmd.args == args
    }

    def 'should create update subcommand with revision'() {
        given:
        def args = ['modulename', 'newrevision']

        when:
        def updateCmd = new CmdModules.UpdateCmd(args)

        then:
        updateCmd.args == args
    }

    def 'should create remove subcommand'() {
        given:
        def args = ['modulename']

        when:
        def removeCmd = new CmdModules.RemoveCmd(args)

        then:
        removeCmd.args == args
    }
}
