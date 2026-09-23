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
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * G4 [S<time>] [P<time>]
 *
 * Dwell (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G4.html">MarlinFirmare G4 doc</a>
 */
data class Dwell(
    /** `S` - time */
    val time: Int? = null,
    /** `P` - time */
    val p: Int? = null,
) : GRq<Dwell> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (time != null) words.add(word('S', time))
        if (p != null) words.add(word('P', p))
        return G(4, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<Dwell> {

        override fun head(): GParameterWord<*> {
            return G(4).head
        }

        override fun decodeParams(tokens: List<GToken>): Dwell {
            return Dwell(
                time = tokens.intOf('S'),
                p = tokens.intOf('P'),
            )
        }
    }
}
