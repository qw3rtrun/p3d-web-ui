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
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M20 [F] [L] [T]
 *
 * List SD Card (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M20.html">MarlinFirmare M20 doc</a>
 */
data class ListSDCard(
    /** `F` */
    val f: Boolean = false,
    /** `L` */
    val l: Boolean = false,
    /** `T` */
    val t: Boolean = false,
) : GRq<ListSDCard> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (f) words.add(flag('F'))
        if (l) words.add(flag('L'))
        if (t) words.add(flag('T'))
        return M(20, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ListSDCard> {

        override fun head(): GParameterWord<*> {
            return M(20).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): ListSDCard {
            val params = tokens.toList()
            return ListSDCard(
                f = params.hasWord('F'),
                l = params.hasWord('L'),
                t = params.hasWord('T'),
            )
        }
    }
}
