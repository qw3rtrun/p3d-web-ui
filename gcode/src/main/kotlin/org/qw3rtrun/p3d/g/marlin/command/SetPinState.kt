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
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M42 [I<value>] [T<value>] [P<pin>] S<state>
 *
 * Set Pin State (control).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M42.html">MarlinFirmare M42 doc</a>
 */
data class SetPinState(
    /** `I` */
    val i: Boolean? = null,
    /** `T` */
    val t: Int? = null,
    /** `P` - pin */
    val pin: Int? = null,
    /** `S` - state (required) */
    val state: Int? = null,
) : GRq<SetPinState> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (i != null) words.add(word('I', if (i) 1 else 0))
        if (t != null) words.add(word('T', t))
        if (pin != null) words.add(word('P', pin))
        if (state != null) words.add(word('S', state))
        return M(42, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetPinState> {

        override fun head(): GParameterWord<*> {
            return M(42).head
        }

        override fun decodeParams(params: List<GWord>): SetPinState {
            return SetPinState(
                i = params.boolOf('I'),
                t = params.intOf('T'),
                pin = params.intOf('P'),
                state = params.intOf('S'),
            )
        }
    }
}
