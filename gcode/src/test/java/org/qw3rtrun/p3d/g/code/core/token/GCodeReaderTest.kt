package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Test

class GCodeReaderTest {
    @Test
    fun testIter() {
        val iter = sequenceOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).iterator()
        for (n in iter) {
            if (n == 5) {
                break
            }
        }
        for (n in iter) {
            println(n)
        }

    }

    @Test
    fun testGen() {
        val iter = generateSequence(0) { it + 1 }.take(10).iterator()

        for (n in iter) {
            if (n == 5) {
                break
            }
        }
        for (n in iter) {
            println(n)
        }

    }

    @Test
    fun testYield() {
        val iter = sequence {
            yield(1)
            yield(2)
            yield(3)
            yield(4)
            yield(5)
            yield(6)
        }.iterator()

        for (n in iter) {
            if (n == 5) {
                break
            }
        }
        for (n in iter) {
            println(n)
        }

    }

}
