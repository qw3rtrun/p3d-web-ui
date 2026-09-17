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
import org.qw3rtrun.p3d.g.code.dsl.text
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.stringOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M553 [P<subnet-mask>]
 *
 * Ethernet Subnet Mask (ethernet).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M553.html">MarlinFirmare M553 doc</a>
 */
data class EthernetSubnetMask(
    /** `P` - subnet-mask */
    val subnetMask: String? = null,
) : GRq<EthernetSubnetMask> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (subnetMask != null) words.add(word('P', text(subnetMask)))
        return M(553, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<EthernetSubnetMask> {

        override fun head(): GParameterWord<*> {
            return M(553).head
        }

        override fun decodeParams(params: List<GWord>): EthernetSubnetMask {
            return EthernetSubnetMask(
                subnetMask = params.stringOf('P'),
            )
        }
    }
}
