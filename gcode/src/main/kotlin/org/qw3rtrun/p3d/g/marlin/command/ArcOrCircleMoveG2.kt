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
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G2 [X<pos>] [Y<pos>] [Z<pos>] [A<pos>] [B<pos>] [C<pos>] [U<pos>] [V<pos>] [W<pos>] I<offset> J<offset> R<radius> [E<pos>] [F<rate>] [P<count>] [S<power>]
 *
 * Arc or Circle Move (motion).
 *
 * Marlin documents `I`, `J`, `R` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G2.html">MarlinFirmare G2 doc</a>
 */
data class ArcOrCircleMoveG2(
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
    /** `I` - offset (required) */
    val offset: BigDecimal? = null,
    /** `J` - offset (required) */
    val j: BigDecimal? = null,
    /** `R` - radius (required) */
    val radius: BigDecimal? = null,
    /** `E` - pos */
    val e: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
    /** `P` - count */
    val count: Int? = null,
    /** `S` - power */
    val power: BigDecimal? = null,
) : GRq<ArcOrCircleMoveG2> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(16)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (offset != null) words.add(word('I', offset.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (radius != null) words.add(word('R', radius.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        if (count != null) words.add(word('P', count))
        if (power != null) words.add(word('S', power.toPlainString()))
        return G(2, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ArcOrCircleMoveG2> {

        override fun head(): GParameterWord<*> {
            return G(2).head
        }

        override fun decodeParams(tokens: List<GToken>): ArcOrCircleMoveG2 {
            return ArcOrCircleMoveG2(
                pos = tokens.decimalOf('X'),
                y = tokens.decimalOf('Y'),
                z = tokens.decimalOf('Z'),
                a = tokens.decimalOf('A'),
                b = tokens.decimalOf('B'),
                c = tokens.decimalOf('C'),
                u = tokens.decimalOf('U'),
                v = tokens.decimalOf('V'),
                w = tokens.decimalOf('W'),
                offset = tokens.decimalOf('I'),
                j = tokens.decimalOf('J'),
                radius = tokens.decimalOf('R'),
                e = tokens.decimalOf('E'),
                rate = tokens.decimalOf('F'),
                count = tokens.intOf('P'),
                power = tokens.decimalOf('S'),
            )
        }
    }
}
