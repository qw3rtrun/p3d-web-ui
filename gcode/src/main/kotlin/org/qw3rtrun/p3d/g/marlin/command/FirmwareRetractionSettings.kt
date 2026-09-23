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
 * M207 [S<length>] [W<length>] [F<feedrate>] [Z<length>]
 *
 * Firmware Retraction Settings (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M207.html">MarlinFirmare M207 doc</a>
 */
data class FirmwareRetractionSettings(
    /** `S` - length */
    val length: BigDecimal? = null,
    /** `W` - length */
    val w: BigDecimal? = null,
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `Z` - length */
    val z: BigDecimal? = null,
) : GRq<FirmwareRetractionSettings> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (length != null) words.add(word('S', length.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(207, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<FirmwareRetractionSettings> {

        override fun head(): GParameterWord<*> {
            return M(207).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): FirmwareRetractionSettings {
            val params = tokens.toList()
            return FirmwareRetractionSettings(
                length = params.decimalOf('S'),
                w = params.decimalOf('W'),
                feedrate = params.decimalOf('F'),
                z = params.decimalOf('Z'),
            )
        }
    }
}
