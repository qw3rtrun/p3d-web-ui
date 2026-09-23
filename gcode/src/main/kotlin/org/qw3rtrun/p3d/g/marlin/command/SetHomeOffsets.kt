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
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M206 [P<offset>] [T<offset>] [X<offset>] [Y<offset>] [Z<offset>] [A<offset>] [B<offset>] [C<offset>] [U<offset>] [V<offset>] [W<offset>]
 *
 * Set Home Offsets (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M206.html">MarlinFirmare M206 doc</a>
 */
data class SetHomeOffsets(
    /** `P` - offset */
    val offset: BigDecimal? = null,
    /** `T` - offset */
    val t: BigDecimal? = null,
    /** `X` - offset */
    val x: BigDecimal? = null,
    /** `Y` - offset */
    val y: BigDecimal? = null,
    /** `Z` - offset */
    val z: BigDecimal? = null,
    /** `A` - offset */
    val a: BigDecimal? = null,
    /** `B` - offset */
    val b: BigDecimal? = null,
    /** `C` - offset */
    val c: BigDecimal? = null,
    /** `U` - offset */
    val u: BigDecimal? = null,
    /** `V` - offset */
    val v: BigDecimal? = null,
    /** `W` - offset */
    val w: BigDecimal? = null,
) : GRq<SetHomeOffsets> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(11)
        if (offset != null) words.add(word('P', offset.toPlainString()))
        if (t != null) words.add(word('T', t.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        return M(206, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetHomeOffsets> {

        override fun head(): GParameterWord<*> {
            return M(206).head
        }

        override fun decodeParams(tokens: List<GToken>): SetHomeOffsets {
            return SetHomeOffsets(
                offset = tokens.decimalOf('P'),
                t = tokens.decimalOf('T'),
                x = tokens.decimalOf('X'),
                y = tokens.decimalOf('Y'),
                z = tokens.decimalOf('Z'),
                a = tokens.decimalOf('A'),
                b = tokens.decimalOf('B'),
                c = tokens.decimalOf('C'),
                u = tokens.decimalOf('U'),
                v = tokens.decimalOf('V'),
                w = tokens.decimalOf('W'),
            )
        }
    }
}
