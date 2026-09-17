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
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M912 [I<value>] [X] [Y] [Z] [E<value>]
 *
 * Clear TMC OT Pre-Warn (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M912.html">MarlinFirmare M912 doc</a>
 */
data class ClearTMCOTPreWarn(
    /** `I` */
    val i: Int? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: BigDecimal? = null,
) : GRq<ClearTMCOTPreWarn> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (i != null) words.add(word('I', i))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e != null) words.add(word('E', e.toPlainString()))
        return M(912, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ClearTMCOTPreWarn> {

        override fun head(): GParameterWord<*> {
            return M(912).head
        }

        override fun decodeParams(params: List<GWord>): ClearTMCOTPreWarn {
            return ClearTMCOTPreWarn(
                i = params.intOf('I'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.decimalOf('E'),
            )
        }
    }
}
