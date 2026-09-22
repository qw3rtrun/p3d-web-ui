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
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M204 [P<accel>] [R<accel>] [T<accel>] [S<accel>]
 *
 * Set Starting Acceleration (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M204.html">MarlinFirmare M204 doc</a>
 */
data class SetStartingAcceleration(
    /** `P` - accel */
    val accel: BigDecimal? = null,
    /** `R` - accel */
    val r: BigDecimal? = null,
    /** `T` - accel */
    val t: BigDecimal? = null,
    /** `S` - accel */
    val s: BigDecimal? = null,
) : GRq<SetStartingAcceleration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (accel != null) words.add(word('P', accel.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (t != null) words.add(word('T', t.toPlainString()))
        if (s != null) words.add(word('S', s.toPlainString()))
        return M(204, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetStartingAcceleration> {

        override fun head(): GParameterWord<*> {
            return M(204).head
        }

        override fun decodeParams(params: List<GWord>): SetStartingAcceleration {
            return SetStartingAcceleration(
                accel = params.decimalOf('P'),
                r = params.decimalOf('R'),
                t = params.decimalOf('T'),
                s = params.decimalOf('S'),
            )
        }
    }
}
