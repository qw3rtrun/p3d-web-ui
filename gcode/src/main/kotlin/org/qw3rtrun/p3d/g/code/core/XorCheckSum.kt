package org.qw3rtrun.p3d.g.code.core

import org.qw3rtrun.p3d.g.code.core.token.GInt

interface CheckSumCalculator {
    fun add(ch: Char)
    fun get(): GInt
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