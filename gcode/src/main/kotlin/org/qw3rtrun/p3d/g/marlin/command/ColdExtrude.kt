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
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M302 [S<temp>] [P<value>]
 *
 * Cold Extrude (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M302.html">MarlinFirmare M302 doc</a>
 */
data class ColdExtrude(
    /** `S` - temp */
    val temp: BigDecimal? = null,
    /** `P` */
    val p: Boolean? = null,
) : GRq<ColdExtrude> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (temp != null) words.add(word('S', temp.toPlainString()))
        if (p != null) words.add(word('P', if (p) 1 else 0))
        return M(302, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ColdExtrude> {

        override fun head(): GParameterWord<*> {
            return M(302).head
        }

        override fun decodeParams(tokens: List<GToken>): ColdExtrude {
            return ColdExtrude(
                temp = tokens.decimalOf('S'),
                p = tokens.boolOf('P'),
            )
        }
    }
}
