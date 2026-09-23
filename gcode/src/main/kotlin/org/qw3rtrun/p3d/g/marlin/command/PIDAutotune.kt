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
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M303 [E<index>] [C<count>] [S<temp>] [U<value>] [D]
 *
 * PID autotune (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M303.html">MarlinFirmare M303 doc</a>
 */
data class PIDAutotune(
    /** `E` - index */
    val index: Int? = null,
    /** `C` - count */
    val count: Int? = null,
    /** `S` - temp */
    val temp: BigDecimal? = null,
    /** `U` */
    val u: Boolean? = null,
    /** `D` */
    val d: Boolean = false,
) : GRq<PIDAutotune> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (index != null) words.add(word('E', index))
        if (count != null) words.add(word('C', count))
        if (temp != null) words.add(word('S', temp.toPlainString()))
        if (u != null) words.add(word('U', if (u) 1 else 0))
        if (d) words.add(flag('D'))
        return M(303, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<PIDAutotune> {

        override fun head(): GParameterWord<*> {
            return M(303).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): PIDAutotune {
            val params = tokens.toList()
            return PIDAutotune(
                index = params.intOf('E'),
                count = params.intOf('C'),
                temp = params.decimalOf('S'),
                u = params.boolOf('U'),
                d = params.hasWord('D'),
            )
        }
    }
}
