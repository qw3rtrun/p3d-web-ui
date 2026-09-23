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
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M494 [T] [O] [X] [Y] [Z] [E]
 *
 * FT Motion Trajectory Smoothing (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M494.html">MarlinFirmare M494 doc</a>
 */
data class FTMotionTrajectorySmoothing(
    /** `T` */
    val t: Boolean = false,
    /** `O` */
    val o: Boolean = false,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: Boolean = false,
) : GRq<FTMotionTrajectorySmoothing> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (t) words.add(flag('T'))
        if (o) words.add(flag('O'))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e) words.add(flag('E'))
        return M(494, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<FTMotionTrajectorySmoothing> {

        override fun head(): GParameterWord<*> {
            return M(494).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): FTMotionTrajectorySmoothing {
            val params = tokens.toList()
            return FTMotionTrajectorySmoothing(
                t = params.hasWord('T'),
                o = params.hasWord('O'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
            )
        }
    }
}
