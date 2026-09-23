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
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M43 [P<pin>] [W] [E<value>] [T] [S] [I]
 *
 * Pins Debugging (debug).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M43.html">MarlinFirmare M43 doc</a>
 */
data class PinsDebugging(
    /** `P` - pin */
    val pin: Int? = null,
    /** `W` */
    val w: Boolean = false,
    /** `E` */
    val e: Boolean? = null,
    /** `T` */
    val t: Boolean = false,
    /** `S` */
    val s: Boolean = false,
    /** `I` */
    val i: Boolean = false,
) : GRq<PinsDebugging> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (pin != null) words.add(word('P', pin))
        if (w) words.add(flag('W'))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        if (t) words.add(flag('T'))
        if (s) words.add(flag('S'))
        if (i) words.add(flag('I'))
        return M(43, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<PinsDebugging> {

        override fun head(): GParameterWord<*> {
            return M(43).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): PinsDebugging {
            val params = tokens.toList()
            return PinsDebugging(
                pin = params.intOf('P'),
                w = params.hasWord('W'),
                e = params.boolOf('E'),
                t = params.hasWord('T'),
                s = params.hasWord('S'),
                i = params.hasWord('I'),
            )
        }
    }
}
