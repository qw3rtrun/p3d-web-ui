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
 * M261 A<addr> B<count> [S<value>]
 *
 * I2C Request (i2c).
 *
 * Marlin documents `A`, `B` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M261.html">MarlinFirmare M261 doc</a>
 */
data class I2CRequest(
    /** `A` - addr (required) */
    val addr: Int? = null,
    /** `B` - count (required) */
    val count: Int? = null,
    /** `S` */
    val s: Int? = null,
) : GRq<I2CRequest> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (addr != null) words.add(word('A', addr))
        if (count != null) words.add(word('B', count))
        if (s != null) words.add(word('S', s))
        return M(261, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<I2CRequest> {

        override fun head(): GParameterWord<*> {
            return M(261).head
        }

        override fun decodeParams(params: List<GWord>): I2CRequest {
            return I2CRequest(
                addr = params.intOf('A'),
                count = params.intOf('B'),
                s = params.intOf('S'),
            )
        }
    }
}
