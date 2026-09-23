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
import org.qw3rtrun.p3d.g.code.dsl.bareString
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.beforeStringArg
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.marlin.stringArg
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M118 [P<value>] [<message>]
 *
 * Serial print (hosts).
 *
 * **`message` is a bare rest-of-line string** (spec 3.4a).
 * It carries no letter, it is written last because everything to the end of the
 * line belongs to it, and it cannot contain `;` - every parser reads that as the
 * start of a comment.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M118.html">MarlinFirmare M118 doc</a>
 */
data class SerialPrint(
    /** `P` */
    val p: Int? = null,
    /** the rest of the line */
    val message: String? = null,
) : GRq<SerialPrint> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (p != null) words.add(word('P', p))
        if (message != null) words.add(bareString(message))
        return M(118, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SerialPrint> {

        override fun head(): GParameterWord<*> {
            return M(118).head
        }

        override fun decodeParams(tokens: List<GToken>): SerialPrint {
            val params = tokens.beforeStringArg('P')
            return SerialPrint(
                p = params.intOf('P'),
                message = tokens.stringArg('P'),
            )
        }
    }
}
