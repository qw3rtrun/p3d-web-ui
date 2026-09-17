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
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M592 [A<coeff>] [B<coeff>] [C<coeff>] [S]
 *
 * Nonlinear Extrusion Control (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M592.html">MarlinFirmare M592 doc</a>
 */
data class NonlinearExtrusionControl(
    /** `A` - coeff */
    val coeff: BigDecimal? = null,
    /** `B` - coeff */
    val b: BigDecimal? = null,
    /** `C` - coeff */
    val c: BigDecimal? = null,
    /** `S` */
    val s: Boolean = false,
) : GRq<NonlinearExtrusionControl> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (coeff != null) words.add(word('A', coeff.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (s) words.add(flag('S'))
        return M(592, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<NonlinearExtrusionControl> {

        override fun head(): GParameterWord<*> {
            return M(592).head
        }

        override fun decodeParams(params: List<GWord>): NonlinearExtrusionControl {
            return NonlinearExtrusionControl(
                coeff = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                s = params.hasWord('S'),
            )
        }
    }
}
