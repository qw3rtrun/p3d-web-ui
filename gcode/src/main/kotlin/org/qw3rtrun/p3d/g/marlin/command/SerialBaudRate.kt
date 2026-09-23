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
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M575 [P] B<baud>
 *
 * Serial baud rate (hosts).
 *
 * Marlin documents `B` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M575.html">MarlinFirmare M575 doc</a>
 */
data class SerialBaudRate(
    /** `P` */
    val p: Boolean = false,
    /** `B` - baud (required) */
    val baud: BigDecimal? = null,
) : GRq<SerialBaudRate> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (p) words.add(flag('P'))
        if (baud != null) words.add(word('B', baud.toPlainString()))
        return M(575, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SerialBaudRate> {

        override fun head(): GParameterWord<*> {
            return M(575).head
        }

        override fun decodeParams(tokens: List<GToken>): SerialBaudRate {
            return SerialBaudRate(
                p = tokens.hasWord('P'),
                baud = tokens.decimalOf('B'),
            )
        }
    }
}
