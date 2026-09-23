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
 * M350 [B<value>] [S<value>] [X<value>] [Y<value>] [Z<value>] [A<value>] [C<value>] [U<value>] [V<value>] [W<value>] [E<value>]
 *
 * Set micro-stepping (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M350.html">MarlinFirmare M350 doc</a>
 */
data class SetMicroStepping(
    /** `B` */
    val b: Int? = null,
    /** `S` */
    val s: Int? = null,
    /** `X` */
    val x: Int? = null,
    /** `Y` */
    val y: Int? = null,
    /** `Z` */
    val z: Int? = null,
    /** `A` */
    val a: Int? = null,
    /** `C` */
    val c: Int? = null,
    /** `U` */
    val u: Int? = null,
    /** `V` */
    val v: Int? = null,
    /** `W` */
    val w: Int? = null,
    /** `E` */
    val e: Int? = null,
) : GRq<SetMicroStepping> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(11)
        if (b != null) words.add(word('B', b))
        if (s != null) words.add(word('S', s))
        if (x != null) words.add(word('X', x))
        if (y != null) words.add(word('Y', y))
        if (z != null) words.add(word('Z', z))
        if (a != null) words.add(word('A', a))
        if (c != null) words.add(word('C', c))
        if (u != null) words.add(word('U', u))
        if (v != null) words.add(word('V', v))
        if (w != null) words.add(word('W', w))
        if (e != null) words.add(word('E', e))
        return M(350, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetMicroStepping> {

        override fun head(): GParameterWord<*> {
            return M(350).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): SetMicroStepping {
            val params = tokens.toList()
            return SetMicroStepping(
                b = params.intOf('B'),
                s = params.intOf('S'),
                x = params.intOf('X'),
                y = params.intOf('Y'),
                z = params.intOf('Z'),
                a = params.intOf('A'),
                c = params.intOf('C'),
                u = params.intOf('U'),
                v = params.intOf('V'),
                w = params.intOf('W'),
                e = params.intOf('E'),
            )
        }
    }
}
