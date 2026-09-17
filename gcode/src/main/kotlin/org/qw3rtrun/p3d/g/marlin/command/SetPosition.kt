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
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G92 [X<pos>] [Y<pos>] [Z<pos>] [A<pos>] [B<pos>] [C<pos>] [U<pos>] [V<pos>] [W<pos>] [E<pos>]
 *
 * Set Position (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G92.html">MarlinFirmare G92 doc</a>
 */
data class SetPosition(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `A` - pos */
    val a: BigDecimal? = null,
    /** `B` - pos */
    val b: BigDecimal? = null,
    /** `C` - pos */
    val c: BigDecimal? = null,
    /** `U` - pos */
    val u: BigDecimal? = null,
    /** `V` - pos */
    val v: BigDecimal? = null,
    /** `W` - pos */
    val w: BigDecimal? = null,
    /** `E` - pos */
    val e: BigDecimal? = null,
) : GRq<SetPosition> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(10)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        return G(92, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetPosition> {

        override fun head(): GParameterWord<*> {
            return G(92).head
        }

        override fun decodeParams(params: List<GWord>): SetPosition {
            return SetPosition(
                pos = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                a = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                u = params.decimalOf('U'),
                v = params.decimalOf('V'),
                w = params.decimalOf('W'),
                e = params.decimalOf('E'),
            )
        }
    }
}
