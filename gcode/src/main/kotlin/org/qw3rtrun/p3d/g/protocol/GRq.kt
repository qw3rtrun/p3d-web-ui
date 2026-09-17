package org.qw3rtrun.p3d.g.protocol

import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GWord

interface GRq<T : GRq<T>> {
    fun encode(): GCommand
}

interface GRqDecoder<out T : GRq<out T>> {
    fun head(): GParameterWord<*>
    fun decodeParams(params: List<GWord>): T

    fun decode(cmd: GCommand): T? {
        return if (head() == cmd.head) decodeParams(cmd.params) else null
    }
}
