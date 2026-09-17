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
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M871 [V<value>] [I<index>] [B] [P] [E] [R]
 *
 * Probe temperature config (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M871.html">MarlinFirmare M871 doc</a>
 */
data class ProbeTemperatureConfig(
    /** `V` - value */
    val value: Int? = null,
    /** `I` - index */
    val index: Int? = null,
    /** `B` */
    val b: Boolean = false,
    /** `P` */
    val p: Boolean = false,
    /** `E` */
    val e: Boolean = false,
    /** `R` */
    val r: Boolean = false,
) : GRq<ProbeTemperatureConfig> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (value != null) words.add(word('V', value))
        if (index != null) words.add(word('I', index))
        if (b) words.add(flag('B'))
        if (p) words.add(flag('P'))
        if (e) words.add(flag('E'))
        if (r) words.add(flag('R'))
        return M(871, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ProbeTemperatureConfig> {

        override fun head(): GParameterWord<*> {
            return M(871).head
        }

        override fun decodeParams(params: List<GWord>): ProbeTemperatureConfig {
            return ProbeTemperatureConfig(
                value = params.intOf('V'),
                index = params.intOf('I'),
                b = params.hasWord('B'),
                p = params.hasWord('P'),
                e = params.hasWord('E'),
                r = params.hasWord('R'),
            )
        }
    }
}
