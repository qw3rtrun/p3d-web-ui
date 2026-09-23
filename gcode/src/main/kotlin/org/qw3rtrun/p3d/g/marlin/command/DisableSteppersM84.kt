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
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M84 [S<seconds>] [X] [Y] [Z] [E] [A] [B] [C] [U] [V] [W]
 *
 * Disable steppers (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M84.html">MarlinFirmare M84 doc</a>
 */
data class DisableSteppersM84(
    /** `S` - seconds */
    val seconds: Int? = null,
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
) : GRq<DisableSteppersM84> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(11)
        if (seconds != null) words.add(word('S', seconds))
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
        return M(84, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<DisableSteppersM84> {

        override fun head(): GParameterWord<*> {
            return M(84).head
        }

        override fun decodeParams(tokens: List<GToken>): DisableSteppersM84 {
            return DisableSteppersM84(
                seconds = tokens.intOf('S'),
                x = tokens.hasWord('X'),
                y = tokens.hasWord('Y'),
                z = tokens.hasWord('Z'),
                e = tokens.hasWord('E'),
                a = tokens.hasWord('A'),
                b = tokens.hasWord('B'),
                c = tokens.hasWord('C'),
                u = tokens.hasWord('U'),
                v = tokens.hasWord('V'),
                w = tokens.hasWord('W'),
            )
        }
    }
}
