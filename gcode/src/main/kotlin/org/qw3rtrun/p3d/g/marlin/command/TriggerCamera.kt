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
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M240 [A<offset>] [B<offset>] [D<ms>] [F<feedrate>] [I<pos>] [J<pos>] [P<ms>] [R<length>] [S<feedrate>] [X<pos>] [Y<pos>] [Z<length>]
 *
 * Trigger Camera (extras).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M240.html">MarlinFirmare M240 doc</a>
 */
data class TriggerCamera(
    /** `A` - offset */
    val offset: BigDecimal? = null,
    /** `B` - offset */
    val b: BigDecimal? = null,
    /** `D` - ms */
    val ms: Int? = null,
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `I` - pos */
    val pos: BigDecimal? = null,
    /** `J` - pos */
    val j: BigDecimal? = null,
    /** `P` - ms */
    val p: Int? = null,
    /** `R` - length */
    val length: BigDecimal? = null,
    /** `S` - feedrate */
    val s: BigDecimal? = null,
    /** `X` - pos */
    val x: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - length */
    val z: BigDecimal? = null,
) : GRq<TriggerCamera> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(12)
        if (offset != null) words.add(word('A', offset.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (ms != null) words.add(word('D', ms))
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (pos != null) words.add(word('I', pos.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (p != null) words.add(word('P', p))
        if (length != null) words.add(word('R', length.toPlainString()))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(240, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<TriggerCamera> {

        override fun head(): GParameterWord<*> {
            return M(240).head
        }

        override fun decodeParams(tokens: List<GToken>): TriggerCamera {
            return TriggerCamera(
                offset = tokens.decimalOf('A'),
                b = tokens.decimalOf('B'),
                ms = tokens.intOf('D'),
                feedrate = tokens.decimalOf('F'),
                pos = tokens.decimalOf('I'),
                j = tokens.decimalOf('J'),
                p = tokens.intOf('P'),
                length = tokens.decimalOf('R'),
                s = tokens.decimalOf('S'),
                x = tokens.decimalOf('X'),
                y = tokens.decimalOf('Y'),
                z = tokens.decimalOf('Z'),
            )
        }
    }
}
