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
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M919 [O<value>] [P<value>] [S<value>] [I<value>] [T<value>] [X] [Y] [Z] [A] [B] [C] [U] [V] [W]
 *
 * TMC Chopper Timing (trinamic).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M919.html">MarlinFirmare M919 doc</a>
 */
data class TMCChopperTiming(
    /** `O` */
    val o: Int? = null,
    /** `P` */
    val p: Int? = null,
    /** `S` */
    val s: Int? = null,
    /** `I` */
    val i: Int? = null,
    /** `T` */
    val t: Int? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `A` */
    val a: Boolean = false,
    /** `B` */
    val b: Boolean = false,
    /** `C` */
    val c: Boolean = false,
    /** `U` */
    val u: Boolean = false,
    /** `V` */
    val v: Boolean = false,
    /** `W` */
    val w: Boolean = false,
) : GRq<TMCChopperTiming> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(14)
        if (o != null) words.add(word('O', o))
        if (p != null) words.add(word('P', p))
        if (s != null) words.add(word('S', s))
        if (i != null) words.add(word('I', i))
        if (t != null) words.add(word('T', t))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (a) words.add(flag('A'))
        if (b) words.add(flag('B'))
        if (c) words.add(flag('C'))
        if (u) words.add(flag('U'))
        if (v) words.add(flag('V'))
        if (w) words.add(flag('W'))
        return M(919, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<TMCChopperTiming> {

        override fun head(): GParameterWord<*> {
            return M(919).head
        }

        override fun decodeParams(params: List<GWord>): TMCChopperTiming {
            return TMCChopperTiming(
                o = params.intOf('O'),
                p = params.intOf('P'),
                s = params.intOf('S'),
                i = params.intOf('I'),
                t = params.intOf('T'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                a = params.hasWord('A'),
                b = params.hasWord('B'),
                c = params.hasWord('C'),
                u = params.hasWord('U'),
                v = params.hasWord('V'),
                w = params.hasWord('W'),
            )
        }
    }
}
