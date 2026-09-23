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
 * M914 [I<value>] [X<value>] [Y<value>] [Z<value>] [A<value>] [B<value>] [C<value>] [U<value>] [V<value>] [W<value>]
 *
 * TMC Bump Sensitivity (trinamic).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M914.html">MarlinFirmare M914 doc</a>
 */
data class TMCBumpSensitivity(
    /** `I` */
    val i: Int? = null,
    /** `X` */
    val x: Int? = null,
    /** `Y` */
    val y: Int? = null,
    /** `Z` */
    val z: Int? = null,
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
) : GRq<TMCBumpSensitivity> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(10)
        if (i != null) words.add(word('I', i))
        if (x != null) words.add(word('X', x))
        if (y != null) words.add(word('Y', y))
        if (z != null) words.add(word('Z', z))
        if (a != null) words.add(word('A', a))
        if (b != null) words.add(word('B', b))
        if (c != null) words.add(word('C', c))
        if (u != null) words.add(word('U', u))
        if (v != null) words.add(word('V', v))
        if (w != null) words.add(word('W', w))
        return M(914, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<TMCBumpSensitivity> {

        override fun head(): GParameterWord<*> {
            return M(914).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): TMCBumpSensitivity {
            val params = tokens.toList()
            return TMCBumpSensitivity(
                i = params.intOf('I'),
                x = params.intOf('X'),
                y = params.intOf('Y'),
                z = params.intOf('Z'),
                a = params.intOf('A'),
                b = params.intOf('B'),
                c = params.intOf('C'),
                u = params.intOf('U'),
                v = params.intOf('V'),
                w = params.intOf('W'),
            )
        }
    }
}
