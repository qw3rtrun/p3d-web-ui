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
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M305 [P<index>] [R<ohm>] [T<ohms>] [B<beta>] [C<coeff>]
 *
 * User Thermistor Parameters (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M305.html">MarlinFirmare M305 doc</a>
 */
data class UserThermistorParameters(
    /** `P` - index */
    val index: Int? = null,
    /** `R` - ohm */
    val ohm: Int? = null,
    /** `T` - ohms */
    val ohms: Int? = null,
    /** `B` - beta */
    val beta: Int? = null,
    /** `C` - coeff */
    val coeff: BigDecimal? = null,
) : GRq<UserThermistorParameters> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (index != null) words.add(word('P', index))
        if (ohm != null) words.add(word('R', ohm))
        if (ohms != null) words.add(word('T', ohms))
        if (beta != null) words.add(word('B', beta))
        if (coeff != null) words.add(word('C', coeff.toPlainString()))
        return M(305, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<UserThermistorParameters> {

        override fun head(): GParameterWord<*> {
            return M(305).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): UserThermistorParameters {
            val params = tokens.toList()
            return UserThermistorParameters(
                index = params.intOf('P'),
                ohm = params.intOf('R'),
                ohms = params.intOf('T'),
                beta = params.intOf('B'),
                coeff = params.decimalOf('C'),
            )
        }
    }
}
