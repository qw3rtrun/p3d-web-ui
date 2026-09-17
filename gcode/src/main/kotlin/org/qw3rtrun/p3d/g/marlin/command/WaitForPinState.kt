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
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M226 P<pin> [S<state>]
 *
 * Wait for Pin State (control).
 *
 * Marlin documents `P` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M226.html">MarlinFirmare M226 doc</a>
 */
data class WaitForPinState(
    /** `P` - pin (required) */
    val pin: Int? = null,
    /** `S` - state */
    val state: Int? = null,
) : GRq<WaitForPinState> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (pin != null) words.add(word('P', pin))
        if (state != null) words.add(word('S', state))
        return M(226, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<WaitForPinState> {

        override fun head(): GParameterWord<*> {
            return M(226).head
        }

        override fun decodeParams(params: List<GWord>): WaitForPinState {
            return WaitForPinState(
                pin = params.intOf('P'),
                state = params.intOf('S'),
            )
        }
    }
}
