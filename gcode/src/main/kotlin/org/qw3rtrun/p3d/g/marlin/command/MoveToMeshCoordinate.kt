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
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G42 [I<pos>] [J<pos>] [F<rate>] [P]
 *
 * Move to mesh coordinate (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G42.html">MarlinFirmare G42 doc</a>
 */
data class MoveToMeshCoordinate(
    /** `I` - pos */
    val pos: BigDecimal? = null,
    /** `J` - pos */
    val j: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
    /** `P` */
    val p: Boolean = false,
) : GRq<MoveToMeshCoordinate> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (pos != null) words.add(word('I', pos.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        if (p) words.add(flag('P'))
        return G(42, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<MoveToMeshCoordinate> {

        override fun head(): GParameterWord<*> {
            return G(42).head
        }

        override fun decodeParams(params: List<GWord>): MoveToMeshCoordinate {
            return MoveToMeshCoordinate(
                pos = params.decimalOf('I'),
                j = params.decimalOf('J'),
                rate = params.decimalOf('F'),
                p = params.hasWord('P'),
            )
        }
    }
}
