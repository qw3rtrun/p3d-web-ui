package org.qw3rtrun.p3d.g.decoder

import org.qw3rtrun.p3d.core.msg.GEvent
import java.util.Optional

class CompositeDecoder(private val encoders: List<GEventDecoder<*>>) : GEventDecoder<GEvent> {

    @Suppress("UNCHECKED_CAST")
    override fun decode(line: String): Optional<GEvent> {
        return encoders.asSequence()
            .map { (it as GEventDecoder<GEvent>).decode(line) }
            .firstOrNull { it.isPresent } ?: Optional.empty()
    }

    override fun test(s: String): Boolean {
        return encoders.any { it.test(s) }
    }
}
