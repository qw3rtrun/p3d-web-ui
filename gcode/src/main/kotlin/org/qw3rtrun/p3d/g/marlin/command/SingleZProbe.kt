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
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G30 [C<value>] [X<pos>] [Y<pos>] [E<value>]
 *
 * Single Z-Probe (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G30.html">MarlinFirmare G30 doc</a>
 */
data class SingleZProbe(
    /** `C` */
    val c: Boolean? = null,
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `E` */
    val e: Boolean? = null,
) : GRq<SingleZProbe> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (c != null) words.add(word('C', if (c) 1 else 0))
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        return G(30, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SingleZProbe> {

        override fun head(): GParameterWord<*> {
            return G(30).head
        }

        override fun decodeParams(params: List<GWord>): SingleZProbe {
            return SingleZProbe(
                c = params.boolOf('C'),
                pos = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                e = params.boolOf('E'),
            )
        }
    }
}
