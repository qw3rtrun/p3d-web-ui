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
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M665 [S<segments-per-second>] [P<theta-pi-offset>] [T<theta-offset>] [A<theta-pi-offset>] [X<theta-pi-offset>] [B<theta-offset>] [Y<theta-offset>]
 *
 * SCARA Configuration.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M665.html">MarlinFirmare M665 doc</a>
 */
data class SCARAConfiguration(
    /** `S` - segments-per-second */
    val segmentsPerSecond: BigDecimal? = null,
    /** `P` - theta-pi-offset */
    val thetaPiOffset: BigDecimal? = null,
    /** `T` - theta-offset */
    val thetaOffset: BigDecimal? = null,
    /** `A` - theta-pi-offset */
    val a: BigDecimal? = null,
    /** `X` - theta-pi-offset */
    val x: BigDecimal? = null,
    /** `B` - theta-offset */
    val b: BigDecimal? = null,
    /** `Y` - theta-offset */
    val y: BigDecimal? = null,
) : GRq<SCARAConfiguration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(7)
        if (segmentsPerSecond != null) words.add(word('S', segmentsPerSecond.toPlainString()))
        if (thetaPiOffset != null) words.add(word('P', thetaPiOffset.toPlainString()))
        if (thetaOffset != null) words.add(word('T', thetaOffset.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        return M(665, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SCARAConfiguration> {

        override fun head(): GParameterWord<*> {
            return M(665).head
        }

        override fun decodeParams(params: List<GWord>): SCARAConfiguration {
            return SCARAConfiguration(
                segmentsPerSecond = params.decimalOf('S'),
                thetaPiOffset = params.decimalOf('P'),
                thetaOffset = params.decimalOf('T'),
                a = params.decimalOf('A'),
                x = params.decimalOf('X'),
                b = params.decimalOf('B'),
                y = params.decimalOf('Y'),
            )
        }
    }
}
