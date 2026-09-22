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
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.G
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
 * G34 [L] [Z<value>] [S<value>] [I<value>] [T<value>] [A<value>] [E<value>] [R]
 *
 * Z Steppers Auto-Alignment (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G34.html">MarlinFirmare G34 doc</a>
 */
data class ZSteppersAutoAlignment(
    /** `L` */
    val l: Boolean = false,
    /** `Z` */
    val z: Int? = null,
    /** `S` */
    val s: Boolean? = null,
    /** `I` */
    val i: Int? = null,
    /** `T` */
    val t: BigDecimal? = null,
    /** `A` */
    val a: BigDecimal? = null,
    /** `E` */
    val e: Boolean? = null,
    /** `R` */
    val r: Boolean = false,
) : GRq<ZSteppersAutoAlignment> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (l) words.add(flag('L'))
        if (z != null) words.add(word('Z', z))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        if (i != null) words.add(word('I', i))
        if (t != null) words.add(word('T', t.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        if (r) words.add(flag('R'))
        return G(34, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ZSteppersAutoAlignment> {

        override fun head(): GParameterWord<*> {
            return G(34).head
        }

        override fun decodeParams(params: List<GWord>): ZSteppersAutoAlignment {
            return ZSteppersAutoAlignment(
                l = params.hasWord('L'),
                z = params.intOf('Z'),
                s = params.boolOf('S'),
                i = params.intOf('I'),
                t = params.decimalOf('T'),
                a = params.decimalOf('A'),
                e = params.boolOf('E'),
                r = params.hasWord('R'),
            )
        }
    }
}
