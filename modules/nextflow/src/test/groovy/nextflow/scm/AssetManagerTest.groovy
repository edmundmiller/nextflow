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

package nextflow.scm

import spock.lang.IgnoreIf

import nextflow.cli.CmdRun
import nextflow.exception.AbortOperationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.junit.Rule
import spock.lang.Ignore
import spock.lang.PendingFeature
import spock.lang.Requires
import spock.lang.Specification
import test.TemporaryPath
import java.nio.file.Path
import java.nio.file.Paths
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@IgnoreIf({System.getenv('NXF_SMOKE')})
class AssetManagerTest extends Specification {

    static String GIT_CONFIG_TEXT = '''
            [remote "origin"]
                url = https://github.com/nextflow-io/nextflow.git
                fetch = +refs/heads/*:refs/remotes/origin/*
            [branch "master"]
                remote = origin
                merge = refs/heads/master
            '''
            .stripIndent()



    static final GIT_CONFIG_LONG = '''
        [core]
            repositoryformatversion = 0
            filemode = true
            bare = false
            logallrefupdates = true
            ignorecase = true
        [remote "origin"]
            url = git@github.com:nextflow-io/nextflow.git
            fetch = +refs/heads/*:refs/remotes/origin/*
        [branch "master"]
            remote = origin
            merge = refs/heads/master
        [submodule "tests"]
            url = git@github.com:nextflow-io/tests.git
        '''
        .stripIndent()

    @Rule
    TemporaryPath tempDir = new TemporaryPath()

    def setup() {
        AssetManager.root = tempDir.root.toFile()
    }

    // Helper method to grab the default brasnch if set in ~/.gitconfig
    String getLocalDefaultBranch() {
        def defaultBranch = 'master'
        def gitconfig = Paths.get(System.getProperty('user.home'),'.gitconfig');
        if(gitconfig.exists()) {
            def config = new Config()
            config.fromText(gitconfig.text)
            defaultBranch = config.getString('init', null, 'defaultBranch') ?: 'master'
        }
        return defaultBranch
    }

    def testList() {

        given:
        def folder = tempDir.getRoot()
        folder.resolve('cbcrg/pipe1').mkdirs()
        folder.resolve('cbcrg/pipe2').mkdirs()
        folder.resolve('ncbi/blast').mkdirs()

        when:
        def list = AssetManager.list()
        then:
        list.sort() == ['cbcrg/pipe1','cbcrg/pipe2','ncbi/blast']

        expect:
        AssetManager.find('blast') == 'ncbi/blast'
        AssetManager.find('pipe1') == 'cbcrg/pipe1'
        AssetManager.find('pipe') as Set == ['cbcrg/pipe1', 'cbcrg/pipe2'] as Set

    }


    def testResolveName() {

        given:
        def folder = tempDir.getRoot()
        folder.resolve('cbcrg/pipe1').mkdirs()
        folder.resolve('cbcrg/pipe2').mkdirs()
        folder.resolve('ncbi/blast').mkdirs()

        def manager = new AssetManager()

        when:
        def result = manager.resolveName('x/y')
        then:
        result == 'x/y'

        when:
        result = manager.resolveName('blast')
        then:
        result == 'ncbi/blast'

        when:
        result = manager.resolveName('ncbi/blast/script.nf')
        then:
        result == 'ncbi/blast'

        when:
        result = manager.resolveName('blast/script.nf')
        then:
        result == 'ncbi/blast'

        when:
        manager.resolveName('pipe')
        then:
        thrown(AbortOperationException)

        when:
        manager.resolveName('pipe/alpha/beta')
        then:
        thrown(AbortOperationException)

        when:
        result = manager.resolveName('../blast/script.nf')
        then:
        thrown(AbortOperationException)

        when:
        result = manager.resolveName('./blast/script.nf')
        then:
        thrown(AbortOperationException)

    }


    @Requires({System.getenv('NXF_GITHUB_ACCESS_TOKEN')})
    def testPull() {

        given:
        def folder = tempDir.getRoot()
        def token = System.getenv('NXF_GITHUB_ACCESS_TOKEN')
        def manager = new AssetManager().build('nextflow-io/hello', [providers: [github: [auth: token]]])

        when:
        manager.download()
        then:
        folder.resolve('nextflow-io/hello/.git').isDirectory()

        when:
        manager.download()
        then:
        noExceptionThrown()

    }


    @Requires({System.getenv('NXF_GITHUB_ACCESS_TOKEN')})
    def testPullTagTwice() {

        given:
        def folder = tempDir.getRoot()
        def token = System.getenv('NXF_GITHUB_ACCESS_TOKEN')
        def manager = new AssetManager().build('nextflow-io/hello', [providers: [github: [auth: token]]])

        when:
        manager.download("v1.2")
        then:
        folder.resolve('nextflow-io/hello/.git').isDirectory()

        when:
        manager.download("v1.2")
        then:
        noExceptionThrown()
    }

    // The hashes used here are NOT associated with tags.
    @Requires({System.getenv('NXF_GITHUB_ACCESS_TOKEN')})
    def testPullHashTwice() {

        given:
        def folder = tempDir.getRoot()
        def token = System.getenv('NXF_GITHUB_ACCESS_TOKEN')
        def manager = new AssetManager().build('nextflow-io/hello', [providers: [github: [auth: token]]])

        when:
        manager.download("6b9515aba6c7efc6a9b3f273ce116fc0c224bf68")
        then:
        folder.resolve('nextflow-io/hello/.git').isDirectory()

        when:
        def result = manager.download("6b9515aba6c7efc6a9b3f273ce116fc0c224bf68")
        then:
        noExceptionThrown()
        result == "Already-up-to-date"
    }


    // Downloading a branch first and then pulling the branch
    // should work fine, unlike with tags.
    @Requires({System.getenv('NXF_GITHUB_ACCESS_TOKEN')})
    def testPullBranchTwice() {

        given:
        def folder = tempDir.getRoot()
        def token = System.getenv('NXF_GITHUB_ACCESS_TOKEN')
        def manager = new AssetManager().build('nextflow-io/hello', [providers: [github: [auth: token]]])

        when:
        manager.download("mybranch")
        then:
        folder.resolve('nextflow-io/hello/.git').isDirectory()

        when:
        manager.download("mybranch")
        then:
        noExceptionThrown()
    }

    // First clone a repo with a tag, then forget to include the -r argument
    // when you execute nextflow.
    // Note that while the download will work, execution will fail subsequently
    // at a separate check - this just tests that we don't fail because of a detached head.
    @Requires({System.getenv('NXF_GITHUB_ACCESS_TOKEN')})
    def testPullTagThenBranch() {

        given:
        def folder = tempDir.getRoot()
        def token = System.getenv('NXF_GITHUB_ACCESS_TOKEN')
        def manager = new AssetManager().build('nextflow-io/hello', [providers: [github: [auth: token]]])

        when:
        manager.download("v1.2")
        then:
        folder.resolve('nextflow-io/hello/.git').isDirectory()

        when:
        manager.download()
        then:
        noExceptionThrown()
    }


    @Requires({System.getenv('NXF_GITHUB_ACCESS_TOKEN')})
    def testClone() {

        given:
        def dir = tempDir.getRoot()
        def token = System.getenv('NXF_GITHUB_ACCESS_TOKEN')
        def manager = new AssetManager().build('nextflow-io/hello', [providers:[github: [auth: token]]])

        when:
        manager.clone(dir.toFile())

        then:
        dir.resolve('README.md').exists()
        dir.resolve('.git').isDirectory()

    }

    def testGetScriptName() {

        given:
        def dir = tempDir.getRoot()
        dir.resolve('sub1').mkdir()
        dir.resolve('sub1/nextflow.config').text = "manifest.mainScript = 'pippo.nf'"
        dir.resolve('sub2').mkdir()

        when:
        def holder = new AssetManager()
        holder.localPath = dir.resolve('sub1').toFile()
        then:
        holder.getMainScriptName() == 'pippo.nf'

        when:
        holder = new AssetManager()
        holder.localPath = dir.resolve('sub2').toFile()
        then:
        holder.getMainScriptName() == 'main.nf'

        when:
        holder = new AssetManager()
        holder.localPath = dir.resolve('sub1').toFile()
        holder.resolveName('nextflow/hello')
        then:
        holder.getMainScriptName() == 'pippo.nf'

        when:
        holder = new AssetManager()
        holder.localPath = dir.resolve('sub1').toFile()
        holder.resolveName('nextflow/hello/my-script.nf')
        then:
        holder.getMainScriptName() == 'my-script.nf'

        when:
        holder = new AssetManager()
        holder.localPath = dir.resolve('sub1').toFile()
        then:
        holder.resolveName('nextflow-io/hello/x/y/z/my-script.nf') == 'nextflow-io/hello'
        holder.getMainScriptName() == 'x/y/z/my-script.nf'

        when:
        holder = new AssetManager()
        holder.localPath = dir.resolve('sub1').toFile()
        then:
        holder.resolveName('nextflow-io/hello/my-script.nf') == 'nextflow-io/hello'
        holder.getMainScriptName() == 'my-script.nf'

        when:
        holder = new AssetManager()
        holder.localPath = dir.resolve('sub1').toFile()
        then:
        holder.resolveName('hello/my-script.nf') == 'nextflow-io/hello'
        holder.getMainScriptName() == 'my-script.nf'

    }

    def testCreateProviderFor(){

        when:
        def manager = new AssetManager()
        def repo = manager.createHubProvider('github')
        then:
        repo instanceof GithubRepositoryProvider

        when:
        manager = new AssetManager()
        repo = manager.createHubProvider('bitbucket')
        then:
        repo instanceof BitbucketRepositoryProvider

        when:
        manager = new AssetManager()
        repo = manager.createHubProvider('gitlab')
        then:
        repo instanceof GitlabRepositoryProvider

        when:
        manager = [:] as AssetManager
        manager.createHubProvider('xxx')
        then:
        thrown(AbortOperationException)

    }


    def 'should read manifest file' () {

        given:
        def config =
                '''
                manifest {
                    homePage = 'http://foo.com'
                    mainScript = 'hello.nf'
                    defaultBranch = 'super-stuff'
                    description = 'This pipeline do this and that'
                    author = 'Hi Dude'
                }
                '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')
        then:
        holder.getMainScriptName() == 'hello.nf'
        holder.manifest.getDefaultBranch() == 'super-stuff'
        holder.manifest.getHomePage() == 'http://foo.com'
        holder.manifest.getDescription() == 'This pipeline do this and that'
        holder.manifest.getAuthor() == 'Hi Dude'

    }

    def 'should read a default tag from the manifest file' () {

        given:
        def config =
                '''
                manifest {
                    homePage = 'http://foo.com'
                    mainScript = 'hello.nf'
                    defaultBranch = '1.0.0'
                    description = 'This pipeline do this and that'
                    author = 'Hi Dude'
                }
                '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')
        then:
        holder.manifest.getDefaultBranch() == '1.0.0'

    }

    def 'should return default main script file' () {

        given:
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = 'empty: 1'
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.getMainScriptName() == 'main.nf'
        holder.getHomePage() == 'https://github.com/foo/bar'
        holder.manifest.getDefaultBranch() == 'master'
        holder.manifest.getDescription() == null

    }

    def 'should parse git config and return the remote url' () {

        given:
        def dir = tempDir.root
        dir.resolve('.git').mkdir()
        dir.resolve('.git/config').text = GIT_CONFIG_LONG

        when:
        def manager = new AssetManager().setLocalPath(dir.toFile())
        then:
        manager.getGitConfigRemoteUrl() == 'git@github.com:nextflow-io/nextflow.git'

    }

    def 'should parse git config and return the remote host' () {

        given:
        def dir = tempDir.root
        dir.resolve('.git').mkdir()
        dir.resolve('.git/config').text = GIT_CONFIG_LONG

        when:
        def manager = new AssetManager().setLocalPath(dir.toFile())
        then:
        manager.getGitConfigRemoteDomain() == 'github.com'

    }

    def 'should create a script file object' () {

        given:
        def dir = tempDir.root
        // create the repo dir
        dir.resolve('main.nf').text = "println 'Hello world'"
        dir.resolve('nextflow.config').text = 'manifest {  }'
        dir.resolve('foo.nf').text = 'this is foo content'

        def init = Git.init()
        def repo = init.setDirectory( dir.toFile() ).call()
        repo.add().addFilepattern('.').call()
        def commit = repo.commit().setSign(false).setAll(true).setMessage('First commit').call()
        repo.close()

        // append fake remote data
        dir.resolve('.git/config').text = GIT_CONFIG_TEXT

        when:
        def p = Mock(RepositoryProvider) { getRepositoryUrl() >> 'https://github.com/nextflow-io/nextflow' }
        and:
        def manager = new AssetManager(provider: p)
                .setLocalPath(dir.toFile())
                .setProject('nextflow-io/nextflow')
        and:
        def script = manager.getScriptFile()
        then:
        script.localPath == dir
        script.commitId == commit.name()
        script.revision == getLocalDefaultBranch()
        script.parent == dir
        script.text == "println 'Hello world'"
        script.repository == 'https://github.com/nextflow-io/nextflow'
        script.projectName == 'nextflow-io/nextflow'

        when:
        p = Mock(RepositoryProvider) { getRepositoryUrl() >> 'https://github.com/nextflow-io/nextflow' }
        and:
        manager = new AssetManager(provider: p)
                .setLocalPath(dir.toFile())
                .setProject('nextflow-io/nextflow')
        and:
        script = manager.getScriptFile('foo.nf')
        then:
        script.localPath == dir
        script.commitId == commit.name()
        script.revision == getLocalDefaultBranch()
        script.parent == dir
        script.text == "this is foo content"
        script.repository == 'https://github.com/nextflow-io/nextflow'
        script.projectName == 'nextflow-io/nextflow'

    }

    def 'should return project name from git url' () {

        AssetManager manager
        String result

        when:
        manager = new AssetManager()
        result = manager.resolveNameFromGitUrl('nextflow/pipe')
        then:
        result == null
        manager.hub == null

        when:
        manager = new AssetManager()
        result = manager.resolveNameFromGitUrl('https://gitlab.com/pditommaso/hello.git')
        then:
        result == 'pditommaso/hello'
        manager.hub == 'gitlab'

        when:
        manager = new AssetManager()
        result = manager.resolveNameFromGitUrl('file:/user/repo/projects/hello.git')
        then:
        result == 'local/hello'
        manager.hub == 'file:/user/repo/projects'

        when:
        manager = new AssetManager()
        manager.providerConfigs.add( new ProviderConfig('local-scm', [platform: 'github', server: 'http://foo.bar.com']) )
        result = manager.resolveNameFromGitUrl('https://foo.bar.com/project/xyz.git')
        then:
        result == 'project/xyz'
        manager.hub == 'local-scm'

        when:
        manager = new AssetManager()
        manager.providerConfigs.add( new ProviderConfig('gitea', [platform: 'gitea', server: 'http://my-server.org/sub1']) )
        result = manager.resolveNameFromGitUrl('http://my-server.org/sub1/foo/bar.git')
        then:
        result == 'foo/bar'
        manager.hub == 'gitea'

    }

    @Requires({System.getenv('NXF_GITHUB_ACCESS_TOKEN')})
    def 'should download branch specified'() {

        given:
        def folder = tempDir.getRoot()
        def token = System.getenv('NXF_GITHUB_ACCESS_TOKEN')
        def manager = new AssetManager().build('nextflow-io/nf-test-branch', [providers: [github: [auth: token]]])

        when:
        manager.download("dev")
        then:
        folder.resolve('nextflow-io/nf-test-branch/.git').isDirectory()
        and:
        folder.resolve('nextflow-io/nf-test-branch/workflow.nf').text == "println 'Hello'\n"

        when:
        manager.download()
        then:
        noExceptionThrown()
    }

    @Requires({System.getenv('NXF_GITHUB_ACCESS_TOKEN')})
    def 'should fetch main script from branch specified'() {

        given:
        def token = System.getenv('NXF_GITHUB_ACCESS_TOKEN')
        def manager = new AssetManager().build('nextflow-io/nf-test-branch', [providers: [github: [auth: token]]])

        expect:
        manager.checkValidRemoteRepo('dev')
        and:
        manager.getMainScriptName() == 'workflow.nf'

    }

    @Requires({System.getenv('NXF_GITHUB_ACCESS_TOKEN')})
    def 'should download tag specified'() {

        given:
        def folder = tempDir.getRoot()
        def token = System.getenv('NXF_GITHUB_ACCESS_TOKEN')
        def manager = new AssetManager().build('nextflow-io/nf-test-branch', [providers: [github: [auth: token]]])

        when:
        manager.download("v0.1")
        then:
        folder.resolve('nextflow-io/nf-test-branch/.git').isDirectory()
        and:
        folder.resolve('nextflow-io/nf-test-branch/workflow.nf').text == "println 'Hello'\n"

        when:
        manager.download()
        then:
        noExceptionThrown()
    }

    @Requires({System.getenv('NXF_GITHUB_ACCESS_TOKEN')})
    def 'should use a default tag'() {

        given:
        def folder = tempDir.getRoot()
        def token = System.getenv('NXF_GITHUB_ACCESS_TOKEN')
        def manager = new AssetManager().build('nextflow-io/nf-test-branch', [providers: [github: [auth: token]]])

        when:
        manager.download("v0.1")
        then:
        folder.resolve('nextflow-io/nf-test-branch/.git').isDirectory()
        and:
        folder.resolve('nextflow-io/nf-test-branch/workflow.nf').text == "println 'Hello'\n"

        when:
        manager.download()
        then:
        noExceptionThrown()
    }

    def 'should work with defaultBranch = master'() {
        given:
        def config = '''
            manifest {
                defaultBranch = 'master'
            }
            '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getDefaultBranch() == 'master'
        holder.manifest.getDefaultRevision() == 'master'
    }

    def 'should work with defaultRevision'() {
        given:
        def config = '''
            manifest {
                defaultRevision = '1.0.0'
                defaultBranch = 'master'
            }
            '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getDefaultRevision() == '1.0.0'
        holder.manifest.getDefaultBranch() == 'master'
    }

    def 'should use version as defaultRevision when available'() {
        given:
        def config = '''
            manifest {
                version = '2.0.0'
                defaultBranch = 'master'
            }
            '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getVersion() == '2.0.0'
        holder.manifest.getDefaultRevision() == '2.0.0'
        holder.manifest.getDefaultBranch() == 'master'
    }

    def 'should prioritize defaultRevision over version'() {
        given:
        def config = '''
            manifest {
                version = '2.0.0' // Development version for main branch
                defaultRevision = '1.0.0' // Latest stable version
                defaultBranch = 'master'
            }
            '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getVersion() == '2.0.0'
        holder.manifest.getDefaultRevision() == '1.0.0'
        holder.manifest.getDefaultBranch() == 'master'
    }

    def 'should work with no defaultBranch'() {
        given:
        def config = '''
            manifest {
            }
            '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getDefaultBranch() == 'master'
        holder.manifest.getDefaultRevision() == 'master'
    }

    def 'should default to version tag if manifest version and no defaultBranch'() {
        given:
        def config = '''
            manifest {
                version = '3.0.0'
            }
            '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getVersion() == '3.0.0'
        holder.manifest.getDefaultRevision() == '3.0.0'
        holder.manifest.getDefaultBranch() == 'master'
    }

    def 'should handle commit hash in defaultRevision'() {
        given:
        def config = '''
            manifest {
                defaultRevision = '6b9515aba6c7efc6a9b3f273ce116fc0c224bf68'
            }
            '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getDefaultRevision() == '6b9515aba6c7efc6a9b3f273ce116fc0c224bf68'
        holder.manifest.getDefaultBranch() == 'master'
    }

    @PendingFeature
    def 'should not warn if project uses a tag as a defaultBranch'() {
        given:
        def ENV = [FOO: '/something', NXF_DEBUG: 'true']

        when:
        new CmdRun(revision: 'xyz')

        then:
        def warning = capture
                .toString()
                .readLines()
                .findResults { line -> line.contains('WARN') ? line : null }
                .join('\n')
        and:
        !warning
        noExceptionThrown()
    }

    @PendingFeature
    def 'should handle development version with stable defaultRevision'() {
        given:
        def config = '''
        manifest {
            version = '2.3.0dev'
            defaultRevision = '2.2.0'
        }
        '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getVersion() == '2.3.0dev'
        holder.manifest.getDefaultRevision() == '2.2.0'
        holder.manifest.isDevelopmentVersion() == true
    }

    @PendingFeature
    def 'should correctly compare development and release versions'() {
        given:
        def config = '''
        manifest {
            version = '2.3.0dev'
            defaultRevision = '2.2.0'
        }
        '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.isVersionGreaterThan('2.2.0') == true
        holder.manifest.isVersionCompatibleWith('2.2.0') == true
    }

    @PendingFeature
    def 'should not warn if project uses a tag as a defaultBranch'() {
        given:
        def ENV = [FOO: '/something', NXF_DEBUG: 'true']

        when:
        new CmdRun(revision: 'xyz')

        then:
        def warning = capture
            .toString()
            .readLines()
            .findResults { line -> line.contains('WARN') ? line : null }
            .join('\n')
        and:
        !warning
        noExceptionThrown()
    }

    // Test version = '2.3.0-RC1' with defaultRevision
    def 'should handle release candidate versions'() {
        given:
        def config = '''
    manifest {
        version = '2.3.0-RC1'
        defaultRevision = '2.2.0'
    }
    '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getVersion() == '2.3.0-RC1'
        holder.manifest.getDefaultRevision() == '2.2.0'
        holder.manifest.isVersionGreaterThan('2.2.0') == true
    }

    // Test version = '2.2.1-hotfix' with defaultRevision = '2.2.0'
    def 'should handle hotfix versions'() {
        given:
        def config = '''
        manifest {
        version = '2.2.1-hotfix'
        defaultRevision = '2.2.0'
    }
    '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getVersion() == '2.2.1-hotfix'
        holder.manifest.getDefaultRevision() == '2.2.0'
        holder.manifest.isVersionGreaterThan('2.2.0') == true
    }

    // Test handling of feature branches while maintaining stable defaultRevision
    def 'should support multiple development branches'() {
        given:
        def config = '''
        manifest {
            version = '2.3.0-dev'
            defaultRevision = '2.2.0'
            defaultBranch = 'feature/new-feature'
        }
    '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getVersion() == '2.3.0-dev'
        holder.manifest.getDefaultRevision() == '2.2.0'
        holder.manifest.getDefaultBranch() == 'feature/new-feature'
    }

    // Test downgrading defaultRevision for emergency rollbacks
    def 'should handle version rollback scenarios'() {
        given:
        def config = '''
        manifest {
        version = '2.2.0'
        defaultRevision = '2.3.0'  // Attempting to rollback to older version
    }
    '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getVersion() == '2.2.0'
        holder.manifest.getDefaultRevision() == '2.3.0'
        holder.manifest.isVersionGreaterThan('2.3.0') == false
    }

    // Test that development version is always ahead of defaultRevision
    def 'should validate version and defaultRevision compatibility'() {
        given:
        def config = '''
        manifest {
        version = '2.1.0'  // Version older than defaultRevision
        defaultRevision = '2.2.0'
    }
    '''
        def dir = tempDir.getRoot()
        dir.resolve('foo/bar').mkdirs()
        dir.resolve('foo/bar/nextflow.config').text = config
        dir.resolve('foo/bar/.git').mkdir()
        dir.resolve('foo/bar/.git/config').text = GIT_CONFIG_TEXT

        when:
        def holder = new AssetManager()
        holder.build('foo/bar')

        then:
        holder.manifest.getVersion() == '2.1.0'
        holder.manifest.getDefaultRevision() == '2.2.0'
        holder.manifest.isVersionGreaterThan('2.2.0') == false
        holder.manifest.isVersionCompatibleWith('2.2.0') == false
    }
}
