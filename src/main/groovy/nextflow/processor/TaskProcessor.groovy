/*
 * Copyright (c) 2012, the authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */
package nextflow.processor
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

import com.google.common.hash.HashCode
import groovy.transform.EqualsAndHashCode
import groovy.transform.PackageScope
import groovy.transform.ToString
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.Dataflow
import groovyx.gpars.dataflow.DataflowQueue
import groovyx.gpars.dataflow.DataflowVariable
import groovyx.gpars.dataflow.operator.DataflowProcessor
import groovyx.gpars.dataflow.operator.PoisonPill
import groovyx.gpars.dataflow.stream.DataflowStreamWriteAdapter
import groovyx.gpars.group.PGroup
import nextflow.Nextflow
import nextflow.Session
import nextflow.exception.MissingFileException
import nextflow.exception.TaskValidationException
import nextflow.executor.AbstractExecutor
import nextflow.script.BaseScript
import nextflow.util.BlankSeparatedList
import nextflow.util.CacheHelper
import nextflow.util.FileHelper
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
abstract class TaskProcessor {

    protected int index

    /**
     * The current workflow execution session
     */
    protected final Session session

    /**
     * The script object which defines this task
     */
    protected final BaseScript ownerScript

    protected PGroup group = Dataflow.retrieveCurrentDFPGroup()

    /**
     * The processor descriptive name
     */
    protected String name

    /**
     * The closure wrapping the script to be executed
     */
    protected Closure code

    protected TaskRun lastRunTask

    protected DataflowProcessor processor

    protected allScalarValues

    protected final AbstractExecutor executor

    protected final TaskConfig taskConfig

    private static final creationLock = new ReentrantLock(true)

    private static final folderLock = new ReentrantLock(true)

    private static final AtomicInteger tasksCount = new AtomicInteger()

    private final random = new Random()

    private Boolean errorShown = Boolean.FALSE

    private final errorLock = new ReentrantLock(true)

    /* for testing purpose - do not remove */
    protected TaskProcessor() { }

    TaskProcessor( AbstractExecutor executor, Session session, BaseScript script, TaskConfig taskConfig, Closure taskBlock ) {
        assert executor
        assert session
        assert script
        assert taskBlock

        this.executor = executor
        this.session = session
        this.ownerScript = script
        this.taskConfig = taskConfig
        this.code = taskBlock

        executor.taskConfig = taskConfig

        if( taskConfig.name ) {
            this.name = taskConfig.name
        }
        else {
            /*
             * generate the processor name if not specified
             */
            this.name = "task_${tasksCount.incrementAndGet()}"
            taskConfig.name = this.name
        }
    }


    TaskConfig getTaskConfig() {
        taskConfig
    }

    Session getSession() { session }

    String getName() { name }

    BaseScript getOwnerScript() { ownerScript }

    /**
     * Launch the 'script' define by the code closure as a local bash script
     *
     * @param code A {@code Closure} retuning a bash script e.g.
     *          <pre>
     *              {
     *                 """
     *                 #!/bin/bash
     *                 do this ${x}
     *                 do that ${y}
     *                 :
     *                 """
     *              }
     *
     * @return {@code this} instance
     */
    def run() {

        if ( !code ) {
            throw new IllegalArgumentException("Missing 'script' attribute")
        }


        /*
         * Normalize the input channels:
         * - at least one input channel have to be provided,
         *   if missing create an dummy 'input' set to true
         */
        log.trace "TaskConfig: ${taskConfig}"
        if( taskConfig.inputs.size() == 0 ) {
            taskConfig.noInput()
        }

        allScalarValues = !taskConfig.inputs.channels.any { !(it instanceof DataflowVariable) }

        /*
         * Normalize the output
         * - even though the output may be empty, let return the stdout as output by default
         */
        if ( taskConfig.outputs.size() == 0 ) {
            def dummy =  allScalarValues ? Nextflow.val() : Nextflow.channel()
            taskConfig.stdout(dummy)
        }

        createOperator()

        /*
         * When there is a single output channel, return let returns that item
         * otherwise return the list
         */
        def result = taskConfig.outputs.channels
        return result.size() == 1 ? result[0] : result
    }

    /**
     * Template method which extending classes have to override in order to
     * create the underlying *dataflow* operator associated with this processor
     *
     */
    protected abstract void createOperator()

    /**
     * @return A string 'she-bang' formatted to the added on top script to be executed.
     * The interpreter to be used define bu the *taskConfig* property {@code shell}
     */
    def getShebangLine() {

        def shell = taskConfig.shell
        String result = shell instanceof List ? shell.join(' ') : shell
        if( result.startsWith('/') ) {
            result = '#!' + result
        }
        else {
            result= '#!/usr/bin/env ' + result
        }

        return result

    }

    /**
     * Remove extra leading and trailing whitespace and newlines chars,
     * also if the script does not start with a {@code shebang} line,
     * add the default by using the current {@code #shell} attribute
     */
    def String normalizeScript(String script) {
        assert script != null

        def result = new StringBuilder()
        result << script.stripIndent().trim()
        result << '\n'

        if( result[0] != '#' || result[1] != '!') {
            result.insert(0, shebangLine +'\n')
        }

        return result.toString()
    }

    /**
     * Given the task script extract the top *she-bang* interpreter declaration removing the {@code #!} characters.
     * @param script The script to be executed
     *
     * @return The interpreter as defined in the she-bang declaration, for example {@code /usr/bin/env perl}
     */
    def String fetchInterpreter( String script ) {
        assert script != null

        if( script[0] == '#' && script[1] == '!') {
            return script.readLines()[0].substring(2)
        }

        return null
    }

    /**
     * Wraps the target method by a closure declaring as many arguments as many are the user declared inputs
     * object.
     *
     * @param n The number of inputs
     * @param method The method which will execute the task
     * @return The operator closure adapter
     */
    final protected Closure createCallbackWrapper( int n, Closure method ) {

        final args = []
        n.times { args << "__p$it" }

        final str = " { ${args.join(',')} -> callback([ ${args.join(',')} ]) }"
        final binding = new Binding( ['callback': method] )
        final result = (Closure)new GroovyShell(binding).evaluate (str)

        return result
    }

    final protected TaskRun createTaskRun() {
        log.debug "Creating a new task > $name"

        TaskRun task = null
        creationLock.lock()
        try {
            def num = session.tasks.size()
            task = new TaskRun(processor: this, id: num, status: TaskRun.Status.PENDING, index: ++index, name: "$name ($index)" )
            session.tasks.put( this, task )
        }
        finally {
            creationLock.unlock()
        }

        return task
    }


    final protected Path createTaskFolder( Path folder, HashCode hash  ) {

        folderLock.lock()
        try {

            if( folder.exists() ) {

                // find another folder name that does NOT exist
                while( true ) {
                    hash = CacheHelper.hasher( [hash.asInt(), random.nextInt() ] ).hash()
                    folder = FileHelper.createWorkFolder(session.workDir, hash)
                    if( !folder.exists() ) {
                        break
                    }
                }
            }

            if( !folder.mkdirs() ) {
                throw new IOException("Unable to create folder: $folder -- check file system permission")
            }

            return folder
        }

        finally {
            folderLock.unlock()
        }

    }


    final boolean checkCachedOutput(TaskRun task, Path folder) {
        if( !folder.exists() ) {
            log.trace "Cached folder does not exists > $folder -- return false"
            // no folder -> no cached result
            return false
        }

        def exitFile = folder.resolve('.exitcode')
        if( FileHelper.empty(exitFile) ) {
            log.trace "Exit file is empty > $exitFile -- return false"
            return false
        }

        def exitValue = exitFile.text.trim()
        def exitCode = exitValue.isInteger() ? exitValue.toInteger() : null
        if( exitCode == null || !(exitCode in taskConfig.validExitCodes) ) {
            log.trace "Exit code is not valid > $exitValue -- return false"
            return false
        }

        try {
            task.exitCode = exitCode
            task.workDirectory = folder

            // -- check if all output resources are available
            collectOutputs(task)
            log.info "Cached task > ${task.name}"

            // -- print out the cached tasks output when 'echo' is true
            if( taskConfig.echo ) {
                def out = executor.getStdOutFile(task)
                if( out instanceof Path )  {
                    System.out.print(out.text)
                }
                else if( out ) {
                    System.out.print(out.toString())
                }
            }

            // -- now bind the results
            bindOutputs(task)
            return true
        }
        catch( MissingFileException e ) {
            log.debug "Missed cache > ${e.getMessage()} -- folder: $folder"
            task.exitCode = Integer.MAX_VALUE
            task.workDirectory = null
            return false
        }

    }

    /**
     * Handles an error raised during the processor execution
     *
     * @param error The exception raised during the task execution
     * @param task The {@code TaskDef} instance which raised the exception
     * @return {@code true} to terminate the processor execution,
     *         {@code false} ignore the error and continue to process other pending tasks
     */
    final protected boolean handleException( Throwable error, TaskRun task = null ) {

        // -- when is a task level error and the user has chosen to ignore error, just report and error message
        //    return 'false' to DO NOT stop the execution
        if( error instanceof TaskValidationException && taskConfig.errorStrategy == ErrorStrategy.IGNORE ) {
            log.warn "Error running task > ${error.getMessage()} -- error is ignored"
            return false
        }

        // -- synchronize on the errorLock to avoid multiple report of the same error
        errorLock.lock()
        try {
            if( errorShown ) { return true }
            errorShown = Boolean.TRUE
        }
        finally {
            errorLock.unlock()
        }


        if( error instanceof TaskValidationException ) {

            // compose a readable error message
            def message = []
            message << error.getMessage()

            if( task ) {
                // print the executed command
                message << "Command executed:"
                task.script.eachLine {
                    message << "  $it"
                }

                // if the echo was disabled show, the program out when there's an error
                message << "\nCommand output:"
                task.stdout?.eachLine {
                    message << "  $it"
                }

                message << "\nCommand work dir:\n  ${task.workDirectory.toString()}"
            }

            message << "\nTip: when you have fixed the problem you may continue the execution appending to the nextflow command line the '-resume' option"

            log.error message.join('\n')

        }
        else {
            log.error("Failed to execute task > '${task?.name ?: name}' -- Check the log file '.nextflow.log' for more details", error)
        }

        session.abort()
        return true
    }

    final protected void finalizeTask( ) {

        // -- when all input values are 'scalar' (not queue or broadcast stream)
        //    stops after the first run
        if( allScalarValues ) {
            log.debug "Finalize > ${name} terminates since all values are scalar"
            // send a poison pill to 'terminate' all downstream channels
            sendPoisonPill()
            processor.terminate()
        }

    }

    final protected synchronized void sendPoisonPill() {

        taskConfig.outputs.eachParam { name, channel ->
            if( channel instanceof DataflowQueue ) {
                log.trace "Sending Poison-pill over $name channel"
                channel.bind( PoisonPill.instance )
            }
            else if( channel instanceof DataflowStreamWriteAdapter ) {
                log.trace "Sending Poison-pill over $name channel"
                channel.bind( PoisonPill.instance )
            }
            else {
                log.trace "Poison pill is not sent over $name channel"
            }

        }
    }

    /**
     * Bind the expected output files to the corresponding output channels
     * @param processor
     */
    synchronized protected void bindOutputs( TaskRun task ) {

        // -- bind each produced file to its own channel
        task.outputs.eachWithIndex { OutParam param, value, index ->

            switch( param ) {
            case StdOutParam:
                log.trace "Task $name > Binding '$value' to stdout"
                processor.bindOutput(index, value instanceof Path ? value.text : value?.toString())
                break

            case FileOutParam:
                log.trace "Task $name > Binding file: '$value' to '${param.name}'"
                if( value instanceof Collection && !(param as FileOutParam).joint ) {
                    value.each { processor.bindOutput(index, it) }
                }
                else {
                    processor.bindOutput(index, value)
                }
                break;

            case ValueOutParam:
                log.trace "Task $name > Binding value: '$value' to '${param.name}'"
                processor.bindOutput(index, value)
                break

            default:
                throw new IllegalArgumentException("Illegal output parameter type: ${param.class.simpleName}")
            }
        }

    }

    /**
     * Once the task has completed this method is invoked to collected all the task results
     *
     * @param task
     */
    final protected void collectOutputs( TaskRun task ) {

        task.outputs.keySet().each { OutParam param ->
            collectOutputs(task, param)
        }

    }

    protected void collectOutputs( TaskRun task, OutParam param ) {
        assert task
        assert param

        switch( param ) {
            case StdOutParam:
                task.setOutput(param, executor.getStdOutFile(task))
                break

            case FileOutParam:
                def all = []
                def fileParam = param as FileOutParam
                // type file parameter can contain a multiple files pattern separating them with a special character
                def entries = fileParam.separatorChar ? fileParam.name.split(/\${fileParam.separatorChar}/) : [fileParam.name]
                // for each of them collect the produced files
                entries.each { String pattern ->
                    def result = executor.collectResultFile(task, pattern)
                    log.debug "Task ${task.name} > collected outputs for pattern '$pattern': $result"

                    if( result instanceof List ) {
                        // filter the result collection
                        if( pattern.startsWith('*') && !fileParam.includeHidden ) {
                            result = filterByRemovingHiddenFiles(result)
                            log.trace "Task ${task.name} > after removing hidden files: ${result}"
                        }

                        // filter the inputs
                        if( !fileParam.includeInputs ) {
                            result = filterByRemovingStagedInputs(task, result)
                            log.trace "Task ${task.name} > after removing staged inputs: ${result}"
                        }

                        all.addAll((List) result)
                    }

                    else if( result ) {
                        all.add(result)
                    }
                }

                task.setOutput( param, all.size()==1 ? all[0] : all )
                break

            case ValueOutParam:
                // look into the task inputs value for an *ValueInParam* entry
                // having the same *name* as the requested output name
                def entry = task.getInputsByType(ValueInParam,EachInParam).find { it.key.name == param.name }
                if( entry ) {
                    task.setOutput( param, entry.value )
                }
                else {
                    log.warn "Not a valid output parameter: '${param.name}' -- only values declared as input can be used in the output section"
                }
                break

            default:
                throw new IllegalArgumentException("Illegal output parameter: ${param.class.simpleName}")

        }
    }

    /**
     * Given a list of {@code Path} removes all the hidden file i.e. the ones which names starts with a dot char
     * @param files A list of {@code Path}
     * @return The result list not containing hidden file entries
     */
    protected List<Path> filterByRemovingHiddenFiles( List<Path> files ) {
        files.findAll { !it.getName().startsWith('.') }
    }

    /**
     * Given a list of {@code Path} removes all the entries which name match the name of
     * file used as input for the specified {@code TaskRun}
     *
     * See TaskRun#getStagedInputs
     *
     * @param task
     * @param files
     * @return
     */
    protected List<Path> filterByRemovingStagedInputs( TaskRun task, List<Path> files ) {

        // get the list of input files
        def List<Path> allStaged = task.getStagedInputs()
        def List<String>  allInputNames = allStaged.collect { it.getName() }

        files.findAll { !allInputNames.contains(it.getName()) }

    }

    /**
     * @return The map holding the shell environment variables for the task to be executed
     */
    def Map<String,String> getProcessEnvironment() {

        def result = [:]

        // add the taskConfig environment entries
        if( session.config.env instanceof Map ) {
            session.config.env.each { name, value ->
                result.put( name, value?.toString() )
            }
        }
        else {
            log.debug "Invalid 'session.config.env' object: ${session.config.env?.class?.name}"
        }


        // pre-pend the 'bin' folder to the task environment
        if( session.binDir ) {
            def path = result['PATH'] ? "${session.binDir}:${result['PATH']}" : session.binDir
            result['PATH'] = path.toString()
        }

        return result
    }

    /**
     * An input file parameter can be provided with any value other than a file.
     * This function normalize a generic value to a {@code Path} create a temporary file
     * in the for it.
     *
     * @param input The input value
     * @param altName The name to be used when a temporary file is created.
     * @return The {@code Path} that will be staged in the task working folder
     */
    protected FileHolder normalizeInputToFile( Object input, String altName ) {

        if( input instanceof Path ) {
            return new FileHolder(input)
        }

        def result = ownerScript.tempFile(altName)
        result.text = input?.toString() ?: ''
        return new FileHolder(input, result)
    }

    protected List<FileHolder> normalizeInputToFiles( Object obj, int count ) {

        def files = (obj instanceof Collection ? obj : [obj]).collect {
            normalizeInputToFile(it, "input.${++count}")
        }

        return files
    }

    protected singleItemOrList( List<FileHolder> items ) {
        assert items
        if( items.size() == 1 ) {
            return items[0].stagePath
        }
        else {
            return new BlankSeparatedList( items *. stagePath )
        }
    }


    /**
     * An input file name may contain wildcards characters which have to be handled coherently
     * given the number of files specified.
     *
     * @param name A file name with may contain a wildcard character star {@code *} or question mark {@code ?}.
     *  Only one occurrence can be specified for star or question mark wildcards.
     *
     * @param value Any value that have to be managed as an input files. Values other than {@code Path} are converted
     * to a string value, using the {@code #toString} method and saved in the local file-system. Value of type {@code Collection}
     * are expanded to multiple values accordingly.
     *
     * @return
     */
    protected List<FileHolder> expandWildcards( String name, List<FileHolder> files ) {
        assert name
        assert files != null

        final result = []
        if( files.size()==0 ) { return result }

        if( name == '*' ) {
            return files
        }

        // no wildcards in the file name
        else if( !name.contains('*') && !name.contains('?') ) {
            /*
             * The name do not contain any wildcards *BUT* when multiple files are provide
             * it is managed like having a 'start' at the end of the file name
             */
            if( files.size()>1 ) {
                name += '*'
            }
            else {
                // just return that name
                result << files[0].withName(name)
                return result
            }
        }

        /*
         * The star wildcard: when a single item is provided, it is simply ignored
         * When a collection of files is provided, the name is expanded to the index number
         */
        if( name.contains('*') ) {
            if( files.size()>1 ) {
                def count = 1
                files.each { FileHolder holder ->
                    def stageName = name.replace('*', (count++).toString())
                    result << holder.withName(stageName)
                }
            }
            else {
                // there's just one value, remove the 'star' wildcards
                def stageName = name.replace('*','')
                result << files[0].withName(stageName)
            }
        }

        /*
         * The question mark wildcards *always* expand to an index number
         * as long as are the number of question mark characters
         */
        else if( name.contains('?') ) {
            def count = 1
            files.each { FileHolder holder ->
                String match = (name =~ /\?+/)[0]
                def replace = (count++).toString().padLeft(match.size(), '0')
                def stageName = name.replace(match, replace)
                result << holder.withName( stageName )
            }

        }

        // not a valid condition
        else {
            throw new IllegalStateException("Invalid file expansion for name: '$name'")
        }

        return result
    }


    @Deprecated
    protected List<FileHolder> expandWildcards( String filePattern, FileHolder... files ) {
        expandWildcards( filePattern, files as List )
    }

    /**
     * Given a map holding variables key-value pairs, create a script fragment
     * exporting the required environment variables
     */
    static String bashEnvironmentScript( Map<String,String> environment ) {

        final script = []
        environment.each { name, value ->
            if( name ==~ /[a-zA-Z_]+[a-zA-Z0-9_]*/ ) {
                script << "export $name='$value'"
            }
            else {
                log.trace "Illegal environment variable name: '${name}'"
            }
        }
        script << ''

        return script.join('\n')
    }



    /**
     * Execute the specified task shell script
     *
     * @param script The script string to be execute, e.g. a BASH script
     * @return {@code TaskDef}
     */
    final void launchTask( TaskRun task ) {
        executor.launchTask(task)
    }


    /**
     * Map used to delegate variable resolution to script scope
     */
    @Slf4j
    static class DelegateMap implements Map {

        @Delegate
        private Map<String,Object> local

        private BaseScript script

        DelegateMap(BaseScript script) {
            this.script = script
            this.local = [:]
        }

        DelegateMap(Map target) {
            assert target != null
            this.script = script
            this.local = target
        }

        @Override
        public Object get(Object property) {

            if( local.containsKey(property) ) {
                return local.get(property)
            }
            else if ( script ){
                try {
                    return script.getProperty(property?.toString())
                }
                catch( MissingPropertyException e ) {
                    log.trace "Unable to find a value for: '\$${property}' on script context"
                }
            }

            // return the variable name prefixed with the '$' char
            // so give a chance to the bash interpreted to evaluate it
            return '$' + property

        }

        @Override
        public put(String property, Object newValue) {
            local.put(property, newValue)
        }
    }

}


/**
 * Implements a special {@code Path} used to stage files in the work area
 */
@ToString(includePackage = false, includeNames = true)
@EqualsAndHashCode
class FileHolder  {

    final def sourceObj

    final Path storePath

    final Path stagePath

    FileHolder( Path inputFile ) {
        assert inputFile
        this.sourceObj = inputFile
        this.storePath = inputFile
        this.stagePath = inputFile.getFileName()
    }

    FileHolder( def origin, Path path ) {
        assert origin != null
        assert path != null

        this.sourceObj = origin
        this.storePath = path
        this.stagePath = path.getFileName()
    }

    protected FileHolder( def source, Path store, def stageName ) {
        this.sourceObj = source
        this.storePath = store
        this.stagePath = stageName instanceof Path ? (Path)stageName : Paths.get(stageName.toString())
    }

    FileHolder withName( def stageName )  {
        new FileHolder( this.sourceObj, this.storePath, stageName )
    }

    @PackageScope
    static FileHolder get( def path, def name = null ) {
        Path storePath = path instanceof Path ? path : Paths.get(path.toString())
        def target = name ? name : storePath.getFileName()
        new FileHolder( path, storePath, target )
    }

}



