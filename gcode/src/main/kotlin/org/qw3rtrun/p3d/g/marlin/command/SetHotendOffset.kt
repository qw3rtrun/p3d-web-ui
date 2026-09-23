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
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M218 [T<index>] [X<offset>] [Y<offset>] [Z<offset>]
 *
 * Set Hotend Offset (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M218.html">MarlinFirmare M218 doc</a>
 */
data class SetHotendOffset(
    /** `T` - index */
    val index: Int? = null,
    /** `X` - offset */
    val offset: BigDecimal? = null,
    /** `Y` - offset */
    val y: BigDecimal? = null,
    /** `Z` - offset */
    val z: BigDecimal? = null,
) : GRq<SetHotendOffset> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (index != null) words.add(word('T', index))
        if (offset != null) words.add(word('X', offset.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(218, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetHotendOffset> {

        override fun head(): GParameterWord<*> {
            return M(218).head
        }

        override fun decodeParams(tokens: List<GToken>): SetHotendOffset {
            return SetHotendOffset(
                index = tokens.intOf('T'),
                offset = tokens.decimalOf('X'),
                y = tokens.decimalOf('Y'),
                z = tokens.decimalOf('Z'),
            )
        }
    }
}
