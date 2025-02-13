package org.qw3rtrun.p3d.g.code

import org.qw3rtrun.p3d.g.code.core.token.*

class G(private val output: (GCommand) -> Unit) {

    fun G(num: Int, vararg tail: GElement) = cmd(GLetter('G'), num.toToken(), *tail)
    fun G(cmd: GElement, vararg tail: GElement) = cmd(GLetter('G'), cmd, *tail)
    fun M(num: Int, vararg tail: GElement) = cmd(GLetter('M'), num.toToken(), *tail)
    fun M(cmd: GElement, vararg tail: GElement) = cmd(GLetter('M'), cmd, *tail)
    fun T(num: Int, vararg tail: GElement) = cmd(GLetter('T'), num.toToken(), *tail)
    fun T(cmd: GElement, vararg tail: GElement) = cmd(GLetter('T'), cmd, *tail)

    private fun cmd(head: GIdentifier, vararg tail: GElement) = cmd(GCommand(head, tail.toList()))
    private fun cmd(cmd: GCommand) = this.output(cmd)
}
