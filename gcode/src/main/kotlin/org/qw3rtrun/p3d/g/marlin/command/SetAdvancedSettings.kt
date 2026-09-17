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
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M205 [X<jerk>] [Y<jerk>] [Z<jerk>] [E<jerk>] [B<value>] [S<value>] [T<value>] [J<deviation>]
 *
 * Set Advanced Settings (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M205.html">MarlinFirmare M205 doc</a>
 */
data class SetAdvancedSettings(
    /** `X` - jerk */
    val jerk: BigDecimal? = null,
    /** `Y` - jerk */
    val y: BigDecimal? = null,
    /** `Z` - jerk */
    val z: BigDecimal? = null,
    /** `E` - jerk */
    val e: BigDecimal? = null,
    /** `B` */
    val b: Int? = null,
    /** `S` */
    val s: BigDecimal? = null,
    /** `T` */
    val t: BigDecimal? = null,
    /** `J` - deviation */
    val deviation: BigDecimal? = null,
) : GRq<SetAdvancedSettings> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (jerk != null) words.add(word('X', jerk.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (b != null) words.add(word('B', b))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (t != null) words.add(word('T', t.toPlainString()))
        if (deviation != null) words.add(word('J', deviation.toPlainString()))
        return M(205, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetAdvancedSettings> {

        override fun head(): GParameterWord<*> {
            return M(205).head
        }

        override fun decodeParams(params: List<GWord>): SetAdvancedSettings {
            return SetAdvancedSettings(
                jerk = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                e = params.decimalOf('E'),
                b = params.intOf('B'),
                s = params.decimalOf('S'),
                t = params.decimalOf('T'),
                deviation = params.decimalOf('J'),
            )
        }
    }
}
