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
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G29 S<value> [I<index>] [J<index>] [X<count>] [Y<count>] [Z<linear>]
 *
 * Bed Leveling (Manual) (calibration).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G29.html">MarlinFirmare G29 doc</a>
 */
data class BedLevelingManual(
    /** `S` (required) */
    val s: Int? = null,
    /** `I` - index */
    val index: Int? = null,
    /** `J` - index */
    val j: Int? = null,
    /** `X` - count */
    val count: Int? = null,
    /** `Y` - count */
    val y: Int? = null,
    /** `Z` - linear */
    val linear: BigDecimal? = null,
) : GRq<BedLevelingManual> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (s != null) words.add(word('S', s))
        if (index != null) words.add(word('I', index))
        if (j != null) words.add(word('J', j))
        if (count != null) words.add(word('X', count))
        if (y != null) words.add(word('Y', y))
        if (linear != null) words.add(word('Z', linear.toPlainString()))
        return G(29, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<BedLevelingManual> {

        override fun head(): GParameterWord<*> {
            return G(29).head
        }

        override fun decodeParams(params: List<GWord>): BedLevelingManual {
            return BedLevelingManual(
                s = params.intOf('S'),
                index = params.intOf('I'),
                j = params.intOf('J'),
                count = params.intOf('X'),
                y = params.intOf('Y'),
                linear = params.decimalOf('Z'),
            )
        }
    }
}
