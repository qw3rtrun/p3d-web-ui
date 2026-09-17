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
 * M605 S<value> [X<value>] [R<value>] [P<value>] [E<value>]
 *
 * Multi Nozzle Mode (control).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M605.html">MarlinFirmare M605 doc</a>
 */
data class MultiNozzleMode(
    /** `S` (required) */
    val s: Int? = null,
    /** `X` */
    val x: BigDecimal? = null,
    /** `R` */
    val r: Int? = null,
    /** `P` */
    val p: Int? = null,
    /** `E` */
    val e: Int? = null,
) : GRq<MultiNozzleMode> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (s != null) words.add(word('S', s))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (r != null) words.add(word('R', r))
        if (p != null) words.add(word('P', p))
        if (e != null) words.add(word('E', e))
        return M(605, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<MultiNozzleMode> {

        override fun head(): GParameterWord<*> {
            return M(605).head
        }

        override fun decodeParams(params: List<GWord>): MultiNozzleMode {
            return MultiNozzleMode(
                s = params.intOf('S'),
                x = params.decimalOf('X'),
                r = params.intOf('R'),
                p = params.intOf('P'),
                e = params.intOf('E'),
            )
        }
    }
}
