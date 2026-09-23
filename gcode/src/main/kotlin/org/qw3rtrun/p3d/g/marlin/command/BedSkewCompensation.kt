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
 * M852 [I] [J] [K] [S]
 *
 * Bed Skew Compensation (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M852.html">MarlinFirmare M852 doc</a>
 */
data class BedSkewCompensation(
    /** `I` */
    val i: Boolean = false,
    /** `J` */
    val j: Boolean = false,
    /** `K` */
    val k: Boolean = false,
    /** `S` */
    val s: Boolean = false,
) : GRq<BedSkewCompensation> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (i) words.add(flag('I'))
        if (j) words.add(flag('J'))
        if (k) words.add(flag('K'))
        if (s) words.add(flag('S'))
        return M(852, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<BedSkewCompensation> {

        override fun head(): GParameterWord<*> {
            return M(852).head
        }

        override fun decodeParams(tokens: List<GToken>): BedSkewCompensation {
            return BedSkewCompensation(
                i = tokens.hasWord('I'),
                j = tokens.hasWord('J'),
                k = tokens.hasWord('K'),
                s = tokens.hasWord('S'),
            )
        }
    }
}
