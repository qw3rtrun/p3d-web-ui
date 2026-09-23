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
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M906 [E<value>] I<value> [T<value>] [X<value>] [Y<value>] [Z<value>]
 *
 * Stepper Motor Current.
 *
 * Marlin documents `I` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M906.html">MarlinFirmare M906 doc</a>
 */
data class StepperMotorCurrent(
    /** `E` */
    val e: Int? = null,
    /** `I` (required) */
    val i: Int? = null,
    /** `T` */
    val t: Int? = null,
    /** `X` */
    val x: Int? = null,
    /** `Y` */
    val y: Int? = null,
    /** `Z` */
    val z: Int? = null,
) : GRq<StepperMotorCurrent> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (e != null) words.add(word('E', e))
        if (i != null) words.add(word('I', i))
        if (t != null) words.add(word('T', t))
        if (x != null) words.add(word('X', x))
        if (y != null) words.add(word('Y', y))
        if (z != null) words.add(word('Z', z))
        return M(906, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<StepperMotorCurrent> {

        override fun head(): GParameterWord<*> {
            return M(906).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): StepperMotorCurrent {
            val params = tokens.toList()
            return StepperMotorCurrent(
                e = params.intOf('E'),
                i = params.intOf('I'),
                t = params.intOf('T'),
                x = params.intOf('X'),
                y = params.intOf('Y'),
                z = params.intOf('Z'),
            )
        }
    }
}
