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
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M421 [I<index>] [J<index>] [X<linear>] [Y<linear>] [Z<linear>] [Q<linear>] [C<value>] [N<value>]
 *
 * Set Mesh Value (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M421.html">MarlinFirmare M421 doc</a>
 */
data class SetMeshValue(
    /** `I` - index */
    val index: Int? = null,
    /** `J` - index */
    val j: Int? = null,
    /** `X` - linear */
    val linear: BigDecimal? = null,
    /** `Y` - linear */
    val y: BigDecimal? = null,
    /** `Z` - linear */
    val z: BigDecimal? = null,
    /** `Q` - linear */
    val q: BigDecimal? = null,
    /** `C` */
    val c: Boolean? = null,
    /** `N` */
    val n: Boolean? = null,
) : GRq<SetMeshValue> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (index != null) words.add(word('I', index))
        if (j != null) words.add(word('J', j))
        if (linear != null) words.add(word('X', linear.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (q != null) words.add(word('Q', q.toPlainString()))
        if (c != null) words.add(word('C', if (c) 1 else 0))
        if (n != null) words.add(word('N', if (n) 1 else 0))
        return M(421, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetMeshValue> {

        override fun head(): GParameterWord<*> {
            return M(421).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): SetMeshValue {
            val params = tokens.toList()
            return SetMeshValue(
                index = params.intOf('I'),
                j = params.intOf('J'),
                linear = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                q = params.decimalOf('Q'),
                c = params.boolOf('C'),
                n = params.boolOf('N'),
            )
        }
    }
}
