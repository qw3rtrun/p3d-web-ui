package org.qw3rtrun.p3d.g.code.core

import org.qw3rtrun.p3d.g.code.core.token.GInt

interface CheckSumCalculator {
    fun add(ch: Char)
    fun get(): GInt

    /**
     * Every character of [str], in order - spec 8.3 covers the bytes as transmitted, so this is how
     * a caller that has built the line as text feeds it in.
     *
     * An index loop rather than `forEach`: this runs for every byte of every line, and a stdlib
     * inline extension is one more thing a port has to recognise for no gain.
     */
    fun add(str: String) {
        var i = 0
        while (i < str.length) {
            add(str[i])
            i++
        }
    }
}

class XorCheckSum : CheckSumCalculator {
    private var sum = 0

    override fun add(ch: Char) {
        sum = sum xor ch.code
    }

    override fun get(): GInt {
        return GInt(sum and 0xff)
    }
}