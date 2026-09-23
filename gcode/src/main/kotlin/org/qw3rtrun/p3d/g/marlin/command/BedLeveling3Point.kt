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
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * G29 [A<value>] [C<value>] [O] [Q<value>] [E<value>] [D<value>] [J<value>] [V<value>]
 *
 * Bed Leveling (3-Point) (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G29.html">MarlinFirmare G29 doc</a>
 */
data class BedLeveling3Point(
    /** `A` */
    val a: Boolean? = null,
    /** `C` */
    val c: Boolean? = null,
    /** `O` */
    val o: Boolean = false,
    /** `Q` */
    val q: Boolean? = null,
    /** `E` */
    val e: Boolean? = null,
    /** `D` */
    val d: Boolean? = null,
    /** `J` */
    val j: Boolean? = null,
    /** `V` */
    val v: Int? = null,
) : GRq<BedLeveling3Point> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (a != null) words.add(word('A', if (a) 1 else 0))
        if (c != null) words.add(word('C', if (c) 1 else 0))
        if (o) words.add(flag('O'))
        if (q != null) words.add(word('Q', if (q) 1 else 0))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        if (d != null) words.add(word('D', if (d) 1 else 0))
        if (j != null) words.add(word('J', if (j) 1 else 0))
        if (v != null) words.add(word('V', v))
        return G(29, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<BedLeveling3Point> {

        override fun head(): GParameterWord<*> {
            return G(29).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): BedLeveling3Point {
            val params = tokens.toList()
            return BedLeveling3Point(
                a = params.boolOf('A'),
                c = params.boolOf('C'),
                o = params.hasWord('O'),
                q = params.boolOf('Q'),
                e = params.boolOf('E'),
                d = params.boolOf('D'),
                j = params.boolOf('J'),
                v = params.intOf('V'),
            )
        }
    }
}
