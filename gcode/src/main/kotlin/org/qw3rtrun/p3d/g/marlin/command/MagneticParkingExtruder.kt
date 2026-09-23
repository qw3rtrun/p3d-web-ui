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
 * M951 [L<value>] [R<value>] [I<value>] [J<value>] [H<value>] [D<value>] [C<value>]
 *
 * Magnetic Parking Extruder (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M951.html">MarlinFirmare M951 doc</a>
 */
data class MagneticParkingExtruder(
    /** `L` */
    val l: BigDecimal? = null,
    /** `R` */
    val r: BigDecimal? = null,
    /** `I` */
    val i: BigDecimal? = null,
    /** `J` */
    val j: BigDecimal? = null,
    /** `H` */
    val h: BigDecimal? = null,
    /** `D` */
    val d: BigDecimal? = null,
    /** `C` */
    val c: BigDecimal? = null,
) : GRq<MagneticParkingExtruder> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(7)
        if (l != null) words.add(word('L', l.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (h != null) words.add(word('H', h.toPlainString()))
        if (d != null) words.add(word('D', d.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        return M(951, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<MagneticParkingExtruder> {

        override fun head(): GParameterWord<*> {
            return M(951).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): MagneticParkingExtruder {
            val params = tokens.toList()
            return MagneticParkingExtruder(
                l = params.decimalOf('L'),
                r = params.decimalOf('R'),
                i = params.decimalOf('I'),
                j = params.decimalOf('J'),
                h = params.decimalOf('H'),
                d = params.decimalOf('D'),
                c = params.decimalOf('C'),
            )
        }
    }
}
