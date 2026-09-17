package org.qw3rtrun.p3d.g.protocol

import org.qw3rtrun.p3d.core.msg.GEvent

interface GRs<T : GRs<T>> : GEvent {
    fun encode(): String
}

interface GRsDecoder<out T : GRs<out T>> {
    fun match(line: String): Boolean
    fun decodeParams(line: String): T

    fun decode(line: String): T? {
        return if (match(line)) decodeParams(line) else null
    }
}

interface GProtoRs<T : GProtoRs<T>> : GRs<T>
interface GEventRs<T : GEventRs<T>> : GRs<T>