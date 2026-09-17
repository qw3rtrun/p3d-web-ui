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
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M414 [S<language-index>]
 *
 * LCD language (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M414.html">MarlinFirmare M414 doc</a>
 */
data class LCDLanguage(
    /** `S` - language-index */
    val languageIndex: Int? = null,
) : GRq<LCDLanguage> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (languageIndex != null) words.add(word('S', languageIndex))
        return M(414, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<LCDLanguage> {

        override fun head(): GParameterWord<*> {
            return M(414).head
        }

        override fun decodeParams(params: List<GWord>): LCDLanguage {
            return LCDLanguage(
                languageIndex = params.intOf('S'),
            )
        }
    }
}
