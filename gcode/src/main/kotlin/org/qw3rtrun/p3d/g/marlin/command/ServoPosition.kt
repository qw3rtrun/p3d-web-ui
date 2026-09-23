// GENERATED FILE - do not edit.
//
// Regenerate with:  python3 tools/marlin/gen_mcommands.py
// Source of truth:  doc/marlin-gcode/commands.json
// Extracted from:   MarlinFirmware/MarlinDocumentation @ 0856d12b0253378bc8d17a246fb09fc8c5437997
// How and why:      tools/marlin/README.md, github.com/qw3rtrun/p3d-web-ui/issues/13

package org.qw3rtrun.p3d.g.marlin.command

import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GToken
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M280 P<index> S<pos>
 *
 * Servo Position (servos).
 *
 * Marlin documents `P`, `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M280.html">MarlinFirmare M280 doc</a>
 */
data class ServoPosition(
    /** `P` - index (required) */
    val index: Int? = null,
    /** `S` - pos (required) */
    val pos: Int? = null,
) : GRq<ServoPosition> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (index != null) words.add(word('P', index))
        if (pos != null) words.add(word('S', pos))
        return M(280, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ServoPosition> {

        override fun head(): GParameterWord<*> {
            return M(280).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): ServoPosition {
            val params = tokens.toList()
            return ServoPosition(
                index = params.intOf('P'),
                pos = params.intOf('S'),
            )
        }
    }
}
