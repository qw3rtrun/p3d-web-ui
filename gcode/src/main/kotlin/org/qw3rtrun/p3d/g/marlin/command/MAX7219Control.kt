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
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.marlin.longOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M7219 [C<column>] [D<row>] [R<row>] [I] [F] [P] [U<index>] [V<bits>] [X<index>] [Y<index>]
 *
 * MAX7219 Control (debug).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M7219.html">MarlinFirmare M7219 doc</a>
 */
data class MAX7219Control(
    /** `C` - column */
    val column: Int? = null,
    /** `D` - row */
    val row: Int? = null,
    /** `R` - row */
    val r: Int? = null,
    /** `I` */
    val i: Boolean = false,
    /** `F` */
    val f: Boolean = false,
    /** `P` */
    val p: Boolean = false,
    /** `U` - index */
    val index: Int? = null,
    /** `V` - bits */
    val bits: Long? = null,
    /** `X` - index */
    val x: Int? = null,
    /** `Y` - index */
    val y: Int? = null,
) : GRq<MAX7219Control> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(10)
        if (column != null) words.add(word('C', column))
        if (row != null) words.add(word('D', row))
        if (r != null) words.add(word('R', r))
        if (i) words.add(flag('I'))
        if (f) words.add(flag('F'))
        if (p) words.add(flag('P'))
        if (index != null) words.add(word('U', index))
        if (bits != null) words.add(word('V', BigDecimal.valueOf(bits)))
        if (x != null) words.add(word('X', x))
        if (y != null) words.add(word('Y', y))
        return M(7219, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<MAX7219Control> {

        override fun head(): GParameterWord<*> {
            return M(7219).head
        }

        override fun decodeParams(params: List<GWord>): MAX7219Control {
            return MAX7219Control(
                column = params.intOf('C'),
                row = params.intOf('D'),
                r = params.intOf('R'),
                i = params.hasWord('I'),
                f = params.hasWord('F'),
                p = params.hasWord('P'),
                index = params.intOf('U'),
                bits = params.longOf('V'),
                x = params.intOf('X'),
                y = params.intOf('Y'),
            )
        }
    }
}
