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
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M250 [C<contrast>]
 *
 * LCD Contrast (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M250.html">MarlinFirmare M250 doc</a>
 */
data class LCDContrast(
    /** `C` - contrast */
    val contrast: Int? = null,
) : GRq<LCDContrast> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (contrast != null) words.add(word('C', contrast))
        return M(250, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<LCDContrast> {

        override fun head(): GParameterWord<*> {
            return M(250).head
        }

        override fun decodeParams(params: List<GWord>): LCDContrast {
            return LCDContrast(
                contrast = params.intOf('C'),
            )
        }
    }
}
