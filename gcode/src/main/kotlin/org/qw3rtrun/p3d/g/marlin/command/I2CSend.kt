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
 * M260 [A<addr>] [B<byte>] [R] [S]
 *
 * I2C Send (i2c).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M260.html">MarlinFirmare M260 doc</a>
 */
data class I2CSend(
    /** `A` - addr */
    val addr: Int? = null,
    /** `B` - byte */
    val byte: Int? = null,
    /** `R` */
    val r: Boolean = false,
    /** `S` */
    val s: Boolean = false,
) : GRq<I2CSend> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (addr != null) words.add(word('A', addr))
        if (byte != null) words.add(word('B', byte))
        if (r) words.add(flag('R'))
        if (s) words.add(flag('S'))
        return M(260, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<I2CSend> {

        override fun head(): GParameterWord<*> {
            return M(260).head
        }

        override fun decodeParams(params: List<GWord>): I2CSend {
            return I2CSend(
                addr = params.intOf('A'),
                byte = params.intOf('B'),
                r = params.hasWord('R'),
                s = params.hasWord('S'),
            )
        }
    }
}
