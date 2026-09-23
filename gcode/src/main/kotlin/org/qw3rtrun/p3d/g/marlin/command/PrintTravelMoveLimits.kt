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
 * M201 [X<accel>] [Y<accel>] [Z<accel>] [E<accel>] [T<index>] [F<value>] [S<percent>]
 *
 * Print / Travel Move Limits (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M201.html">MarlinFirmare M201 doc</a>
 */
data class PrintTravelMoveLimits(
    /** `X` - accel */
    val accel: BigDecimal? = null,
    /** `Y` - accel */
    val y: BigDecimal? = null,
    /** `Z` - accel */
    val z: BigDecimal? = null,
    /** `E` - accel */
    val e: BigDecimal? = null,
    /** `T` - index */
    val index: Int? = null,
    /** `F` */
    val f: Int? = null,
    /** `S` - percent */
    val percent: BigDecimal? = null,
) : GRq<PrintTravelMoveLimits> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(7)
        if (accel != null) words.add(word('X', accel.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (index != null) words.add(word('T', index))
        if (f != null) words.add(word('F', f))
        if (percent != null) words.add(word('S', percent.toPlainString()))
        return M(201, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<PrintTravelMoveLimits> {

        override fun head(): GParameterWord<*> {
            return M(201).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): PrintTravelMoveLimits {
            val params = tokens.toList()
            return PrintTravelMoveLimits(
                accel = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                e = params.decimalOf('E'),
                index = params.intOf('T'),
                f = params.intOf('F'),
                percent = params.decimalOf('S'),
            )
        }
    }
}
