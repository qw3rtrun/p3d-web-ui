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
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G28 [H] [L<value>] [O] [R<linear>] [X] [Y] [Z] [A] [B] [C] [U] [V] [W]
 *
 * Auto Home (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G28.html">MarlinFirmare G28 doc</a>
 */
data class AutoHome(
    /** `H` */
    val h: Boolean = false,
    /** `L` */
    val l: Boolean? = null,
    /** `O` */
    val o: Boolean = false,
    /** `R` - linear */
    val linear: BigDecimal? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `A` */
    val a: Boolean = false,
    /** `B` */
    val b: Boolean = false,
    /** `C` */
    val c: Boolean = false,
    /** `U` */
    val u: Boolean = false,
    /** `V` */
    val v: Boolean = false,
    /** `W` */
    val w: Boolean = false,
) : GRq<AutoHome> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(13)
        if (h) words.add(flag('H'))
        if (l != null) words.add(word('L', if (l) 1 else 0))
        if (o) words.add(flag('O'))
        if (linear != null) words.add(word('R', linear.toPlainString()))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (a) words.add(flag('A'))
        if (b) words.add(flag('B'))
        if (c) words.add(flag('C'))
        if (u) words.add(flag('U'))
        if (v) words.add(flag('V'))
        if (w) words.add(flag('W'))
        return G(28, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<AutoHome> {

        override fun head(): GParameterWord<*> {
            return G(28).head
        }

        override fun decodeParams(params: List<GWord>): AutoHome {
            return AutoHome(
                h = params.hasWord('H'),
                l = params.boolOf('L'),
                o = params.hasWord('O'),
                linear = params.decimalOf('R'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                a = params.hasWord('A'),
                b = params.hasWord('B'),
                c = params.hasWord('C'),
                u = params.hasWord('U'),
                v = params.hasWord('V'),
                w = params.hasWord('W'),
            )
        }
    }
}
