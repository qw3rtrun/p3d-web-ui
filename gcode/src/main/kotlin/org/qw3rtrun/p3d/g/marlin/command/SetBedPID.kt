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
 * M304 [P<value>] [I<value>] [D<value>]
 *
 * Set Bed PID (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M304.html">MarlinFirmare M304 doc</a>
 */
data class SetBedPID(
    /** `P` - value */
    val value: BigDecimal? = null,
    /** `I` - value */
    val i: BigDecimal? = null,
    /** `D` - value */
    val d: BigDecimal? = null,
) : GRq<SetBedPID> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (value != null) words.add(word('P', value.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (d != null) words.add(word('D', d.toPlainString()))
        return M(304, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetBedPID> {

        override fun head(): GParameterWord<*> {
            return M(304).head
        }

        override fun decodeParams(params: List<GWord>): SetBedPID {
            return SetBedPID(
                value = params.decimalOf('P'),
                i = params.decimalOf('I'),
                d = params.decimalOf('D'),
            )
        }
    }
}
