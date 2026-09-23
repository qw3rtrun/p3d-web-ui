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
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M145 [S<index>] [H<temp>] [B<temp>] [F<speed>]
 *
 * Set Material Preset (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M145.html">MarlinFirmare M145 doc</a>
 */
data class SetMaterialPreset(
    /** `S` - index */
    val index: Int? = null,
    /** `H` - temp */
    val temp: Int? = null,
    /** `B` - temp */
    val b: Int? = null,
    /** `F` - speed */
    val speed: Int? = null,
) : GRq<SetMaterialPreset> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (index != null) words.add(word('S', index))
        if (temp != null) words.add(word('H', temp))
        if (b != null) words.add(word('B', b))
        if (speed != null) words.add(word('F', speed))
        return M(145, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetMaterialPreset> {

        override fun head(): GParameterWord<*> {
            return M(145).head
        }

        override fun decodeParams(tokens: List<GToken>): SetMaterialPreset {
            return SetMaterialPreset(
                index = tokens.intOf('S'),
                temp = tokens.intOf('H'),
                b = tokens.intOf('B'),
                speed = tokens.intOf('F'),
            )
        }
    }
}
