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
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M4 [S<power>] [O<power>] [I<mode>]
 *
 * Spindle CCW / Laser On (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M4.html">MarlinFirmare M4 doc</a>
 */
data class SpindleCCWLaserOn(
    /** `S` - power */
    val power: Int? = null,
    /** `O` - power */
    val o: Int? = null,
    /** `I` - mode */
    val mode: Boolean? = null,
) : GRq<SpindleCCWLaserOn> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (power != null) words.add(word('S', power))
        if (o != null) words.add(word('O', o))
        if (mode != null) words.add(word('I', if (mode) 1 else 0))
        return M(4, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SpindleCCWLaserOn> {

        override fun head(): GParameterWord<*> {
            return M(4).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): SpindleCCWLaserOn {
            val params = tokens.toList()
            return SpindleCCWLaserOn(
                power = params.intOf('S'),
                o = params.intOf('O'),
                mode = params.boolOf('I'),
            )
        }
    }
}
