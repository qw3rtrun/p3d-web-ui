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
 * M290 [X<pos>] [Y<pos>] [Z<pos>] [S<pos>] [P<value>]
 *
 * Babystep (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M290.html">MarlinFirmare M290 doc</a>
 */
data class Babystep(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `S` - pos */
    val s: BigDecimal? = null,
    /** `P` */
    val p: Boolean? = null,
) : GRq<Babystep> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (p != null) words.add(word('P', if (p) 1 else 0))
        return M(290, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<Babystep> {

        override fun head(): GParameterWord<*> {
            return M(290).head
        }

        override fun decodeParams(tokens: List<GToken>): Babystep {
            return Babystep(
                pos = tokens.decimalOf('X'),
                y = tokens.decimalOf('Y'),
                z = tokens.decimalOf('Z'),
                s = tokens.decimalOf('S'),
                p = tokens.boolOf('P'),
            )
        }
    }
}
