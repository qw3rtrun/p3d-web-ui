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
 * M210 [X<feedrate>] [Y<feedrate>] [Z<feedrate>] [A<feedrate>] [B<feedrate>] [C<feedrate>] [U<feedrate>] [V<feedrate>] [W<feedrate>]
 *
 * Homing Feedrate (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M210.html">MarlinFirmare M210 doc</a>
 */
data class HomingFeedrate(
    /** `X` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `Y` - feedrate */
    val y: BigDecimal? = null,
    /** `Z` - feedrate */
    val z: BigDecimal? = null,
    /** `A` - feedrate */
    val a: BigDecimal? = null,
    /** `B` - feedrate */
    val b: BigDecimal? = null,
    /** `C` - feedrate */
    val c: BigDecimal? = null,
    /** `U` - feedrate */
    val u: BigDecimal? = null,
    /** `V` - feedrate */
    val v: BigDecimal? = null,
    /** `W` - feedrate */
    val w: BigDecimal? = null,
) : GRq<HomingFeedrate> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(9)
        if (feedrate != null) words.add(word('X', feedrate.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        return M(210, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<HomingFeedrate> {

        override fun head(): GParameterWord<*> {
            return M(210).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): HomingFeedrate {
            val params = tokens.toList()
            return HomingFeedrate(
                feedrate = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                a = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                u = params.decimalOf('U'),
                v = params.decimalOf('V'),
                w = params.decimalOf('W'),
            )
        }
    }
}
