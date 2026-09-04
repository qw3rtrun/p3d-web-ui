package org.qw3rtrun.p3d.g.decoder

import org.qw3rtrun.p3d.core.msg.GEvent
import java.util.Optional
import java.util.function.Function
import java.util.function.Predicate

fun interface GEventDecoder<G : GEvent> : Function<String, Optional<G>>, Predicate<String> {

    fun decode(line: String): Optional<G>

    override fun apply(s: String): Optional<G> = decode(s)

    override fun test(s: String): Boolean = decode(s).isPresent
}
