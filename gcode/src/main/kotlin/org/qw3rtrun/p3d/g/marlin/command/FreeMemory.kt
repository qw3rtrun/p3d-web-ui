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
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M100 [D] [F] [I] [C<n>]
 *
 * Free Memory (debug).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M100.html">MarlinFirmare M100 doc</a>
 */
data class FreeMemory(
    /** `D` */
    val d: Boolean = false,
    /** `F` */
    val f: Boolean = false,
    /** `I` */
    val i: Boolean = false,
    /** `C` - n */
    val n: Int? = null,
) : GRq<FreeMemory> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (d) words.add(flag('D'))
        if (f) words.add(flag('F'))
        if (i) words.add(flag('I'))
        if (n != null) words.add(word('C', n))
        return M(100, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<FreeMemory> {

        override fun head(): GParameterWord<*> {
            return M(100).head
        }

        override fun decodeParams(tokens: List<GToken>): FreeMemory {
            return FreeMemory(
                d = tokens.hasWord('D'),
                f = tokens.hasWord('F'),
                i = tokens.hasWord('I'),
                n = tokens.intOf('C'),
            )
        }
    }
}
