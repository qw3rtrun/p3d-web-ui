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
 * M916 [J<value>] [X<value>] [Y<value>] [Z<value>] [E<value>] [F<feedrate>] [T<current>] [K<value>] [D<second>]
 *
 * L6474 Thermal Warning Test (L6474).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M916.html">MarlinFirmare M916 doc</a>
 */
data class L6474ThermalWarningTest(
    /** `J` */
    val j: Int? = null,
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
    /** `E` */
    val e: BigDecimal? = null,
    /** `F` - feedrate */
    val feedrate: Int? = null,
    /** `T` - current */
    val current: Int? = null,
    /** `K` */
    val k: Int? = null,
    /** `D` - second */
    val second: Int? = null,
) : GRq<L6474ThermalWarningTest> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(9)
        if (j != null) words.add(word('J', j))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (feedrate != null) words.add(word('F', feedrate))
        if (current != null) words.add(word('T', current))
        if (k != null) words.add(word('K', k))
        if (second != null) words.add(word('D', second))
        return M(916, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<L6474ThermalWarningTest> {

        override fun head(): GParameterWord<*> {
            return M(916).head
        }

        override fun decodeParams(tokens: List<GToken>): L6474ThermalWarningTest {
            return L6474ThermalWarningTest(
                j = tokens.intOf('J'),
                x = tokens.decimalOf('X'),
                y = tokens.decimalOf('Y'),
                z = tokens.decimalOf('Z'),
                e = tokens.decimalOf('E'),
                feedrate = tokens.intOf('F'),
                current = tokens.intOf('T'),
                k = tokens.intOf('K'),
                second = tokens.intOf('D'),
            )
        }
    }
}
