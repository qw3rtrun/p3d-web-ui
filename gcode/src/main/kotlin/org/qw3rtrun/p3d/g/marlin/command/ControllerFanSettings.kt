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
 * M710 [S<speed>] [I<speed>] [A<value>] [R<value>] [D<seconds>]
 *
 * Controller Fan settings (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M710.html">MarlinFirmare M710 doc</a>
 */
data class ControllerFanSettings(
    /** `S` - speed */
    val speed: Int? = null,
    /** `I` - speed */
    val i: Int? = null,
    /** `A` */
    val a: Boolean? = null,
    /** `R` */
    val r: Boolean? = null,
    /** `D` - seconds */
    val seconds: Int? = null,
) : GRq<ControllerFanSettings> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (speed != null) words.add(word('S', speed))
        if (i != null) words.add(word('I', i))
        if (a != null) words.add(word('A', if (a) 1 else 0))
        if (r != null) words.add(word('R', if (r) 1 else 0))
        if (seconds != null) words.add(word('D', seconds))
        return M(710, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ControllerFanSettings> {

        override fun head(): GParameterWord<*> {
            return M(710).head
        }

        override fun decodeParams(tokens: List<GToken>): ControllerFanSettings {
            return ControllerFanSettings(
                speed = tokens.intOf('S'),
                i = tokens.intOf('I'),
                a = tokens.boolOf('A'),
                r = tokens.boolOf('R'),
                seconds = tokens.intOf('D'),
            )
        }
    }
}
