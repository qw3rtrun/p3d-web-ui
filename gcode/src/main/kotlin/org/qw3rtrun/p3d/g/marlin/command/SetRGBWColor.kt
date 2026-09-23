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
 * M150 [R<intensity>] [U<intensity>] [B<intensity>] [W<intensity>] [P<intensity>] [I<pixel>] [S<strip>] [K]
 *
 * Set RGB(W) Color (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M150.html">MarlinFirmare M150 doc</a>
 */
data class SetRGBWColor(
    /** `R` - intensity */
    val intensity: Int? = null,
    /** `U` - intensity */
    val u: Int? = null,
    /** `B` - intensity */
    val b: Int? = null,
    /** `W` - intensity */
    val w: Int? = null,
    /** `P` - intensity */
    val p: Int? = null,
    /** `I` - pixel */
    val pixel: Int? = null,
    /** `S` - strip */
    val strip: Int? = null,
    /** `K` */
    val k: Boolean = false,
) : GRq<SetRGBWColor> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (intensity != null) words.add(word('R', intensity))
        if (u != null) words.add(word('U', u))
        if (b != null) words.add(word('B', b))
        if (w != null) words.add(word('W', w))
        if (p != null) words.add(word('P', p))
        if (pixel != null) words.add(word('I', pixel))
        if (strip != null) words.add(word('S', strip))
        if (k) words.add(flag('K'))
        return M(150, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetRGBWColor> {

        override fun head(): GParameterWord<*> {
            return M(150).head
        }

        override fun decodeParams(tokens: List<GToken>): SetRGBWColor {
            return SetRGBWColor(
                intensity = tokens.intOf('R'),
                u = tokens.intOf('U'),
                b = tokens.intOf('B'),
                w = tokens.intOf('W'),
                p = tokens.intOf('P'),
                pixel = tokens.intOf('I'),
                strip = tokens.intOf('S'),
                k = tokens.hasWord('K'),
            )
        }
    }
}
