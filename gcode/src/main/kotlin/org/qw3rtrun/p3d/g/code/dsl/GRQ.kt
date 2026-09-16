package org.qw3rtrun.p3d.g.code.dsl

import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GWord

interface GRQ<T : GRQ<T>> {
    fun encode(): GCommand
}

interface GRQDecoder<out T : GRQ<out T>> {
    fun head(): GParameterWord<*>
    fun decodeParams(params: List<GWord>): T

    fun decode(cmd: GCommand): T? {
        return if (head() == cmd.head) decodeParams(cmd.params) else null
    }
}
