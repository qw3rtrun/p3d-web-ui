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
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M913 [I<value>] [T<value>] [X] [Y] [Z] [A<value>] [B<value>] [C<value>] [U<value>] [V<value>] [W<value>] [E]
 *
 * Set Hybrid Threshold Speed (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M913.html">MarlinFirmare M913 doc</a>
 */
data class SetHybridThresholdSpeed(
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
    val a: Int? = null,
    /** `B` */
    val b: Int? = null,
    /** `C` */
    val c: Int? = null,
    /** `U` */
    val u: Int? = null,
    /** `V` */
    val v: Int? = null,
    /** `W` */
    val w: Int? = null,
    /** `E` */
    val e: Boolean = false,
) : GRq<SetHybridThresholdSpeed> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(12)
        if (i != null) words.add(word('I', i))
        if (t != null) words.add(word('T', t))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (a != null) words.add(word('A', a))
        if (b != null) words.add(word('B', b))
        if (c != null) words.add(word('C', c))
        if (u != null) words.add(word('U', u))
        if (v != null) words.add(word('V', v))
        if (w != null) words.add(word('W', w))
        if (e) words.add(flag('E'))
        return M(913, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetHybridThresholdSpeed> {

        override fun head(): GParameterWord<*> {
            return M(913).head
        }

        override fun decodeParams(params: List<GWord>): SetHybridThresholdSpeed {
            return SetHybridThresholdSpeed(
                i = params.intOf('I'),
                t = params.intOf('T'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                a = params.intOf('A'),
                b = params.intOf('B'),
                c = params.intOf('C'),
                u = params.intOf('U'),
                v = params.intOf('V'),
                w = params.intOf('W'),
                e = params.hasWord('E'),
            )
        }
    }
}
