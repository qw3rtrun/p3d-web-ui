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
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M908 P<address> S<current>
 *
 * Set Trimpot Pins (control).
 *
 * Marlin documents `P`, `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M908.html">MarlinFirmare M908 doc</a>
 */
data class SetTrimpotPins(
    /** `P` - address (required) */
    val address: Int? = null,
    /** `S` - current (required) */
    val current: Int? = null,
) : GRq<SetTrimpotPins> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (address != null) words.add(word('P', address))
        if (current != null) words.add(word('S', current))
        return M(908, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetTrimpotPins> {

        override fun head(): GParameterWord<*> {
            return M(908).head
        }

        override fun decodeParams(params: List<GWord>): SetTrimpotPins {
            return SetTrimpotPins(
                address = params.intOf('P'),
                current = params.intOf('S'),
            )
        }
    }
}
