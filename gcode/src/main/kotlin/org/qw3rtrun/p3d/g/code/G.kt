package org.qw3rtrun.p3d.g.code

import org.qw3rtrun.p3d.g.code.core.token.*

class G(private val output: (GCommand) -> Unit) {

    fun G(num: Int, vararg tail: GWord) = cmd(GLetter('G'), num.toToken(), *tail)
    fun G(cmd: GInt, vararg tail: GWord) = cmd(GLetter('G'), cmd, *tail)
    fun M(num: Int, vararg tail: GWord) = cmd(GLetter('M'), num.toToken(), *tail)
    fun M(cmd: GInt, vararg tail: GWord) = cmd(GLetter('M'), cmd, *tail)
    fun T(num: Int, vararg tail: GWord) = cmd(GLetter('T'), num.toToken(), *tail)
    fun T(cmd: GInt, vararg tail: GWord) = cmd(GLetter('T'), cmd, *tail)

    private fun cmd(head: GIdentifier, cmd: GInt, vararg tail: GWord) =
        cmd(GCommand(head, cmd, tail.toList()))

    private fun cmd(cmd: GCommand) = this.output(cmd)
}
