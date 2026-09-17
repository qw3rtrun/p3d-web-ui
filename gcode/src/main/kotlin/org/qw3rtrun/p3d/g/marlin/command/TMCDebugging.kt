// GENERATED FILE - do not edit.
//
// Regenerate with:  python3 tools/marlin/gen_mcommands.py
// Source of truth:  doc/marlin-gcode/commands.json
// Extracted from:   MarlinFirmware/MarlinDocumentation @ 0856d12b0253378bc8d17a246fb09fc8c5437997
// How and why:      tools/marlin/README.md, doc/todos/11-marlin-commands.md

package org.qw3rtrun.p3d.g.marlin.command

import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
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
 * M122 [I] [X] [Y] [Z] [E] [V] [S<value>] [P<ms>]
 *
 * TMC Debugging (debug).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M122.html">MarlinFirmare M122 doc</a>
 */
data class TMCDebugging(
    /** `I` */
    val i: Boolean = false,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: Boolean = false,
    /** `V` */
    val v: Boolean = false,
    /** `S` */
    val s: Boolean? = null,
    /** `P` - ms */
    val ms: Int? = null,
) : GRq<TMCDebugging> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (i) words.add(flag('I'))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e) words.add(flag('E'))
        if (v) words.add(flag('V'))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        if (ms != null) words.add(word('P', ms))
        return M(122, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<TMCDebugging> {

        override fun head(): GParameterWord<*> {
            return M(122).head
        }

        override fun decodeParams(params: List<GWord>): TMCDebugging {
            return TMCDebugging(
                i = params.hasWord('I'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                v = params.hasWord('V'),
                s = params.boolOf('S'),
                ms = params.intOf('P'),
            )
        }
    }
}
