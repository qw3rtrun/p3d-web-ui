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
 * M106 [I<index>] [S<speed>] [P<index>] [T<value>]
 *
 * Set Fan Speed (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M106.html">MarlinFirmare M106 doc</a>
 */
data class SetFanSpeed(
    /** `I` - index */
    val index: Int? = null,
    /** `S` - speed */
    val speed: Int? = null,
    /** `P` - index */
    val p: Int? = null,
    /** `T` */
    val t: BigDecimal? = null,
) : GRq<SetFanSpeed> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (index != null) words.add(word('I', index))
        if (speed != null) words.add(word('S', speed))
        if (p != null) words.add(word('P', p))
        if (t != null) words.add(word('T', t.toPlainString()))
        return M(106, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetFanSpeed> {

        override fun head(): GParameterWord<*> {
            return M(106).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): SetFanSpeed {
            val params = tokens.toList()
            return SetFanSpeed(
                index = params.intOf('I'),
                speed = params.intOf('S'),
                p = params.intOf('P'),
                t = params.decimalOf('T'),
            )
        }
    }
}
