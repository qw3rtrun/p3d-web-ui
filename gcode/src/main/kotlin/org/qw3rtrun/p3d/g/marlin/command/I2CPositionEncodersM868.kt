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
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M868 [I<index>] [O<value>] [X] [Y] [Z] [E] [U<value>] [P<value>] [S<addr>] [R<value>] [T<value>]
 *
 * I2C Position Encoders (encoder).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M868.html">MarlinFirmare M868 doc</a>
 */
data class I2CPositionEncodersM868(
    /** `I` - index */
    val index: Int? = null,
    /** `O` */
    val o: Boolean? = null,
    /** `X` - axis */
    val axis: Boolean = false,
    /** `Y` - axis */
    val y: Boolean = false,
    /** `Z` - axis */
    val z: Boolean = false,
    /** `E` - axis */
    val e: Boolean = false,
    /** `U` */
    val u: Boolean? = null,
    /** `P` */
    val p: Int? = null,
    /** `S` - addr */
    val addr: Int? = null,
    /** `R` */
    val r: Boolean? = null,
    /** `T` */
    val t: BigDecimal? = null,
) : GRq<I2CPositionEncodersM868> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(11)
        if (index != null) words.add(word('I', index))
        if (o != null) words.add(word('O', if (o) 1 else 0))
        if (axis) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e) words.add(flag('E'))
        if (u != null) words.add(word('U', if (u) 1 else 0))
        if (p != null) words.add(word('P', p))
        if (addr != null) words.add(word('S', addr))
        if (r != null) words.add(word('R', if (r) 1 else 0))
        if (t != null) words.add(word('T', t.toPlainString()))
        return M(868, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<I2CPositionEncodersM868> {

        override fun head(): GParameterWord<*> {
            return M(868).head
        }

        override fun decodeParams(tokens: List<GToken>): I2CPositionEncodersM868 {
            return I2CPositionEncodersM868(
                index = tokens.intOf('I'),
                o = tokens.boolOf('O'),
                axis = tokens.hasWord('X'),
                y = tokens.hasWord('Y'),
                z = tokens.hasWord('Z'),
                e = tokens.hasWord('E'),
                u = tokens.boolOf('U'),
                p = tokens.intOf('P'),
                addr = tokens.intOf('S'),
                r = tokens.boolOf('R'),
                t = tokens.decimalOf('T'),
            )
        }
    }
}
