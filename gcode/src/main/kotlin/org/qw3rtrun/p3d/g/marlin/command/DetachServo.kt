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
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M282 P<index>
 *
 * Detach Servo (servos).
 *
 * Marlin documents `P` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M282.html">MarlinFirmare M282 doc</a>
 */
data class DetachServo(
    /** `P` - index (required) */
    val index: Int? = null,
) : GRq<DetachServo> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (index != null) words.add(word('P', index))
        return M(282, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<DetachServo> {

        override fun head(): GParameterWord<*> {
            return M(282).head
        }

        override fun decodeParams(params: List<GWord>): DetachServo {
            return DetachServo(
                index = params.intOf('P'),
            )
        }
    }
}
