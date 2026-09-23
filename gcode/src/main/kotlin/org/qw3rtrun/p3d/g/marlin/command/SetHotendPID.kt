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
 * M301 [E<index>] [P<value>] [I<value>] [D<value>] [C<value>] [L<value>] [F<value>]
 *
 * Set Hotend PID (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M301.html">MarlinFirmare M301 doc</a>
 */
data class SetHotendPID(
    /** `E` - index */
    val index: Int? = null,
    /** `P` - value */
    val value: BigDecimal? = null,
    /** `I` - value */
    val i: BigDecimal? = null,
    /** `D` - value */
    val d: BigDecimal? = null,
    /** `C` - value */
    val c: BigDecimal? = null,
    /** `L` - value */
    val l: BigDecimal? = null,
    /** `F` - value */
    val f: BigDecimal? = null,
) : GRq<SetHotendPID> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(7)
        if (index != null) words.add(word('E', index))
        if (value != null) words.add(word('P', value.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (d != null) words.add(word('D', d.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (f != null) words.add(word('F', f.toPlainString()))
        return M(301, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetHotendPID> {

        override fun head(): GParameterWord<*> {
            return M(301).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): SetHotendPID {
            val params = tokens.toList()
            return SetHotendPID(
                index = params.intOf('E'),
                value = params.decimalOf('P'),
                i = params.decimalOf('I'),
                d = params.decimalOf('D'),
                c = params.decimalOf('C'),
                l = params.decimalOf('L'),
                f = params.decimalOf('F'),
            )
        }
    }
}
