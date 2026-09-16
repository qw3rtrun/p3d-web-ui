package org.qw3rtrun.p3d.g.code.dsl

import org.qw3rtrun.p3d.core.msg.GEvent

interface GRS<T : GRS<T>> : GEvent {
    fun encode(): String
}

interface GRSDecoder<out T : GRS<out T>> {
    fun match(line: String): Boolean
    fun decodeParams(line: String): T

    fun decode(line: String): T? {
        return if (match(line)) decodeParams(line) else null
    }
}