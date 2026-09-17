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
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M420 [L<value>] [S<value>] [V<value>] [T<value>] [Z<linear>] [C<negative_offset>]
 *
 * Bed Leveling State (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M420.html">MarlinFirmare M420 doc</a>
 */
data class BedLevelingState(
    /** `L` */
    val l: Int? = null,
    /** `S` */
    val s: Boolean? = null,
    /** `V` */
    val v: Boolean? = null,
    /** `T` */
    val t: Int? = null,
    /** `Z` - linear */
    val linear: BigDecimal? = null,
    /** `C` - negative_offset */
    val negativeOffset: BigDecimal? = null,
) : GRq<BedLevelingState> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (l != null) words.add(word('L', l))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        if (v != null) words.add(word('V', if (v) 1 else 0))
        if (t != null) words.add(word('T', t))
        if (linear != null) words.add(word('Z', linear.toPlainString()))
        if (negativeOffset != null) words.add(word('C', negativeOffset.toPlainString()))
        return M(420, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<BedLevelingState> {

        override fun head(): GParameterWord<*> {
            return M(420).head
        }

        override fun decodeParams(params: List<GWord>): BedLevelingState {
            return BedLevelingState(
                l = params.intOf('L'),
                s = params.boolOf('S'),
                v = params.boolOf('V'),
                t = params.intOf('T'),
                linear = params.decimalOf('Z'),
                negativeOffset = params.decimalOf('C'),
            )
        }
    }
}
