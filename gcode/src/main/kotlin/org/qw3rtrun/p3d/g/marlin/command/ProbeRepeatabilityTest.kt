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
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M48 [C<value>] [E<engage>] [L<legs>] [P<count>] [S<value>] [V<level>] [X<pos>] [Y<pos>]
 *
 * Probe Repeatability Test (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M48.html">MarlinFirmare M48 doc</a>
 */
data class ProbeRepeatabilityTest(
    /** `C` */
    val c: Boolean? = null,
    /** `E` - engage */
    val engage: Boolean? = null,
    /** `L` - legs */
    val legs: Int? = null,
    /** `P` - count */
    val count: Int? = null,
    /** `S` */
    val s: Int? = null,
    /** `V` - level */
    val level: Int? = null,
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
) : GRq<ProbeRepeatabilityTest> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (c != null) words.add(word('C', if (c) 1 else 0))
        if (engage != null) words.add(word('E', if (engage) 1 else 0))
        if (legs != null) words.add(word('L', legs))
        if (count != null) words.add(word('P', count))
        if (s != null) words.add(word('S', s))
        if (level != null) words.add(word('V', level))
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        return M(48, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ProbeRepeatabilityTest> {

        override fun head(): GParameterWord<*> {
            return M(48).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): ProbeRepeatabilityTest {
            val params = tokens.toList()
            return ProbeRepeatabilityTest(
                c = params.boolOf('C'),
                engage = params.boolOf('E'),
                legs = params.intOf('L'),
                count = params.intOf('P'),
                s = params.intOf('S'),
                level = params.intOf('V'),
                pos = params.decimalOf('X'),
                y = params.decimalOf('Y'),
            )
        }
    }
}
