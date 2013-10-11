/*
<<<<<<< HEAD
 * Copyright (c) 2012, the authors.
=======
 * Copyright (c) 2013, the authors.
>>>>>>> beatriz/master
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

package nextflow
import java.nio.file.Paths

import groovyx.gpars.dataflow.DataflowVariable
import groovyx.gpars.dataflow.operator.PoisonPill
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class NextflowTest extends Specification {


    def testList() {

        expect:
        Nextflow.list('a') == ['a']
        Nextflow.list(1,2,3) == [1,2,3]
        Nextflow.list(1..9, 'a'..'z') == (1..9) + ('a'..'z')

        Nextflow.list('hola') == ['hola']
        Nextflow.list('alpha','beta') == ['alpha','beta']
    }

    def testListFromChannel() {

        when:
        def var = new DataflowVariable()
        var << 1
        then:
        Nextflow.list(var) == [1]

        when:
        def ch = Nextflow.channel(1,2,9)
        then:
        Nextflow.list(ch) == [1,2,9]


        when:
        def ch1 = Nextflow.channel(1,2,3)
        def ch2 = Nextflow.channel('x','y','z')
        Nextflow.list(ch1,ch2)
        then:
        thrown(IllegalArgumentException)


        when:
        def b = Nextflow.broadcast()
        def ch3 = b.createReadChannel()
        b << 4 << 5 << 6 << PoisonPill.instance
        then:
        Nextflow.list(ch3) == [4,5,6]

    }

    def testFile() {

        expect:
        Nextflow.file('file.log').toFile() == new File('file.log').canonicalFile
        Nextflow.file('relative/file.test').toFile() == new File( new File('.').canonicalFile, 'relative/file.test')
        Nextflow.file('/user/home/file.log').toFile() == new File('/user/home/file.log')
        Nextflow.file('~').toFile() == new File( System.getProperty('user.home') )
        Nextflow.file('~/file.test').toFile() == new File( System.getProperty('user.home'), 'file.test' )
        Nextflow.file('~file.name').toFile() == new File('~file.name').canonicalFile

    }



    def testFile2() {

        when:
        def current = new File('.').canonicalPath

        then:
        Nextflow.file('hola').toString() == current + '/hola'
        Nextflow.file( new File('path/file.txt') ).toString() == current + '/path/file.txt'
        Nextflow.file( Paths.get('some/path') ).toString() == current + '/some/path'
        Nextflow.file( '/abs/path/file.txt' ) == Paths.get('/abs/path/file.txt')


    }

    def testFileWithWildcards() {

        setup:
        new File('hola1').text = 'abc'
        new File('helo2').text = 'abc'
        new File('hello3').text = 'abc'

        def h1 = Paths.get('hola1').toAbsolutePath()
        def h2 = Paths.get('helo2').toAbsolutePath()
        def h3 = Paths.get('hello3').toAbsolutePath()

        expect:
        Nextflow.file('ciao*') == []
        Nextflow.file('hel*').sort() == [ h2, h3 ].sort()
        Nextflow.file('hol??') == [ h1  ]


        cleanup:
        h1.delete()
        h2.delete()
        h3.delete()

    }

//    def testStringAsPath() {
//
//        when:
//        Nextflow.registerTypes()
//        def x = 'hola'
//        then:
//        // Java String
//        'hola' as Path == Paths.get('hola')
//        // Groovy GString
//        "$x" as Path == Paths.get('hola')
//
//    }


}
