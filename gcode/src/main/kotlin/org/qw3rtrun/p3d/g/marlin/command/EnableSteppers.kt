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
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M17 [X] [Y] [Z] [E] [A] [B] [C] [U] [V] [W]
 *
 * Enable Steppers (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M17.html">MarlinFirmare M17 doc</a>
 */
data class EnableSteppers(
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: Boolean = false,
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
) : GRq<EnableSteppers> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(10)
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e) words.add(flag('E'))
        if (a) words.add(flag('A'))
        if (b) words.add(flag('B'))
        if (c) words.add(flag('C'))
        if (u) words.add(flag('U'))
        if (v) words.add(flag('V'))
        if (w) words.add(flag('W'))
        return M(17, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<EnableSteppers> {

        override fun head(): GParameterWord<*> {
            return M(17).head
        }

        override fun decodeParams(params: List<GWord>): EnableSteppers {
            return EnableSteppers(
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
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
