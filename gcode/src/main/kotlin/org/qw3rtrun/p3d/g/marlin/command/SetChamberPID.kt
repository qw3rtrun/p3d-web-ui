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
 * M309 [P<value>] [I<value>] [D<value>]
 *
 * Set Chamber PID (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M309.html">MarlinFirmare M309 doc</a>
 */
data class SetChamberPID(
    /** `P` - value */
    val value: BigDecimal? = null,
    /** `I` - value */
    val i: BigDecimal? = null,
    /** `D` - value */
    val d: BigDecimal? = null,
) : GRq<SetChamberPID> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (value != null) words.add(word('P', value.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (d != null) words.add(word('D', d.toPlainString()))
        return M(309, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetChamberPID> {

        override fun head(): GParameterWord<*> {
            return M(309).head
        }

        override fun decodeParams(params: List<GWord>): SetChamberPID {
            return SetChamberPID(
                value = params.decimalOf('P'),
                i = params.decimalOf('I'),
                d = params.decimalOf('D'),
            )
        }
    }
}
