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
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M600 [T<index>] [E<pos>] [U<pos>] [L<pos>] [X<pos>] [Y<pos>] [Z<pos>] [B<beeps>] [R<temp>]
 *
 * Filament Change (filament).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M600.html">MarlinFirmare M600 doc</a>
 */
data class FilamentChange(
    /** `T` - index */
    val index: Int? = null,
    /** `E` - pos */
    val pos: BigDecimal? = null,
    /** `U` - pos */
    val u: BigDecimal? = null,
    /** `L` - pos */
    val l: BigDecimal? = null,
    /** `X` - pos */
    val x: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `B` - beeps */
    val beeps: Int? = null,
    /** `R` - temp */
    val temp: Int? = null,
) : GRq<FilamentChange> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(9)
        if (index != null) words.add(word('T', index))
        if (pos != null) words.add(word('E', pos.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (beeps != null) words.add(word('B', beeps))
        if (temp != null) words.add(word('R', temp))
        return M(600, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<FilamentChange> {

        override fun head(): GParameterWord<*> {
            return M(600).head
        }

        override fun decodeParams(tokens: List<GToken>): FilamentChange {
            return FilamentChange(
                index = tokens.intOf('T'),
                pos = tokens.decimalOf('E'),
                u = tokens.decimalOf('U'),
                l = tokens.decimalOf('L'),
                x = tokens.decimalOf('X'),
                y = tokens.decimalOf('Y'),
                z = tokens.decimalOf('Z'),
                beeps = tokens.intOf('B'),
                temp = tokens.intOf('R'),
            )
        }
    }
}
