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
 * M1 [S<sec>] [P<ms>] [<message>]
 *
 * Unconditional stop (motion).
 *
 * **`message` is a bare rest-of-line string** (spec 3.4a).
 * It carries no letter, it is written last because everything to the end of the
 * line belongs to it, and it cannot contain `;` - every parser reads that as the
 * start of a comment.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M1.html">MarlinFirmare M1 doc</a>
 */
data class UnconditionalStopM1(
    /** `S` - sec */
    val sec: Int? = null,
    /** `P` - ms */
    val ms: Int? = null,
    /** the rest of the line */
    val message: String? = null,
) : GRq<UnconditionalStopM1> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (sec != null) words.add(word('S', sec))
        if (ms != null) words.add(word('P', ms))
        if (message != null) words.add(bareString(message))
        return M(1, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<UnconditionalStopM1> {

        override fun head(): GParameterWord<*> {
            return M(1).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): UnconditionalStopM1 {
            val all = tokens.toList()
            val params = all.beforeStringArg('S', 'P')
            return UnconditionalStopM1(
                sec = params.intOf('S'),
                ms = params.intOf('P'),
                message = all.stringArg('S', 'P'),
            )
        }
    }
}
