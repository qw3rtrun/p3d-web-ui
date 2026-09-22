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
 * G33 [C<value>] [E<value>] [F<value>] [P<value>] [T<value>] [V<value>] [O<value>] [R<value>] [S] [X] [Y] [Z]
 *
 * Delta Auto Calibration ([ calibration, delta ]).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G33.html">MarlinFirmare G33 doc</a>
 */
data class DeltaAutoCalibration(
    /** `C` */
    val c: BigDecimal? = null,
    /** `E` */
    val e: Boolean? = null,
    /** `F` */
    val f: Int? = null,
    /** `P` */
    val p: Int? = null,
    /** `T` */
    val t: Boolean? = null,
    /** `V` */
    val v: Int? = null,
    /** `O` */
    val o: Boolean? = null,
    /** `R` */
    val r: BigDecimal? = null,
    /** `S` */
    val s: Boolean = false,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
) : GRq<DeltaAutoCalibration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(12)
        if (c != null) words.add(word('C', c.toPlainString()))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        if (f != null) words.add(word('F', f))
        if (p != null) words.add(word('P', p))
        if (t != null) words.add(word('T', if (t) 1 else 0))
        if (v != null) words.add(word('V', v))
        if (o != null) words.add(word('O', if (o) 1 else 0))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (s) words.add(flag('S'))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        return G(33, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<DeltaAutoCalibration> {

        override fun head(): GParameterWord<*> {
            return G(33).head
        }

        override fun decodeParams(params: List<GWord>): DeltaAutoCalibration {
            return DeltaAutoCalibration(
                c = params.decimalOf('C'),
                e = params.boolOf('E'),
                f = params.intOf('F'),
                p = params.intOf('P'),
                t = params.boolOf('T'),
                v = params.intOf('V'),
                o = params.boolOf('O'),
                r = params.decimalOf('R'),
                s = params.hasWord('S'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
            )
        }
    }
}
