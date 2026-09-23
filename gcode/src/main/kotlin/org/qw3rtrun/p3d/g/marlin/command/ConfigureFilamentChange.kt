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
 * M603 [T<index>] [U<pos>] [L<pos>]
 *
 * Configure Filament Change (filament).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M603.html">MarlinFirmare M603 doc</a>
 */
data class ConfigureFilamentChange(
    /** `T` - index */
    val index: Int? = null,
    /** `U` - pos */
    val pos: BigDecimal? = null,
    /** `L` - pos */
    val l: BigDecimal? = null,
) : GRq<ConfigureFilamentChange> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (index != null) words.add(word('T', index))
        if (pos != null) words.add(word('U', pos.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        return M(603, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ConfigureFilamentChange> {

        override fun head(): GParameterWord<*> {
            return M(603).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): ConfigureFilamentChange {
            val params = tokens.toList()
            return ConfigureFilamentChange(
                index = params.intOf('T'),
                pos = params.decimalOf('U'),
                l = params.decimalOf('L'),
            )
        }
    }
}
