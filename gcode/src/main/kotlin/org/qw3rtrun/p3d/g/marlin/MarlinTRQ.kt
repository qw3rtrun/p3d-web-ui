// GENERATED FILE - do not edit.
//
// Regenerate with:  python3 tools/marlin/gen_mcommands.py
// Source of truth:  doc/marlin-gcode/commands.json
// Extracted from:   MarlinFirmware/MarlinDocumentation @ 0856d12b0253378bc8d17a246fb09fc8c5437997
// How and why:      tools/marlin/README.md, doc/todos/11-marlin-commands.md
//
// Marlin's `T` commands, one class each, all implementing GRq and all written the same
// way: `encode()` builds the command with the code/dsl builders, and the companion object
// implements GRqDecoder, so `head()` names the command and `decodeParams` reads one back
// without an instance - `SomeCommand.decode(cmd)`. Every parameter is optional and absent
// by default, so a bare instance encodes to the bare command - `M105` and `M105 T0` are
// different commands and both have to be sayable.
//
// 8 classes over 8 distinct codes and 16 parameter slots. `G`, `M` and `T` are in three
// files only because there are 295 classes in all; MarlinRQ.kt registers every one of them
// and is the single place that sees all three. Generated rather than typed because a
// transposed parameter letter is invisible in review and shows up when a printer answers
// `echo:Unknown command`.

package org.qw3rtrun.p3d.g.marlin

import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.T
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * T0 [F<feedrate>] [S<value>]
 *
 * Select or Report Tool (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/T0.html">MarlinFirmare T0 doc</a>
 */
data class SelectOrReportToolT0(
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `S` */
    val s: Boolean? = null,
) : GRq<SelectOrReportToolT0> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return T(0, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SelectOrReportToolT0> {

        override fun head(): GParameterWord<*> {
            return T(0).head
        }

        override fun decodeParams(params: List<GWord>): SelectOrReportToolT0 {
            return SelectOrReportToolT0(
                feedrate = params.decimalOf('F'),
                s = params.boolOf('S'),
            )
        }
    }
}

/**
 * T1 [F<feedrate>] [S<value>]
 *
 * Select or Report Tool (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/T1.html">MarlinFirmare T1 doc</a>
 */
data class SelectOrReportToolT1(
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `S` */
    val s: Boolean? = null,
) : GRq<SelectOrReportToolT1> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return T(1, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SelectOrReportToolT1> {

        override fun head(): GParameterWord<*> {
            return T(1).head
        }

        override fun decodeParams(params: List<GWord>): SelectOrReportToolT1 {
            return SelectOrReportToolT1(
                feedrate = params.decimalOf('F'),
                s = params.boolOf('S'),
            )
        }
    }
}

/**
 * T2 [F<feedrate>] [S<value>]
 *
 * Select or Report Tool (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/T2.html">MarlinFirmare T2 doc</a>
 */
data class SelectOrReportToolT2(
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `S` */
    val s: Boolean? = null,
) : GRq<SelectOrReportToolT2> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return T(2, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SelectOrReportToolT2> {

        override fun head(): GParameterWord<*> {
            return T(2).head
        }

        override fun decodeParams(params: List<GWord>): SelectOrReportToolT2 {
            return SelectOrReportToolT2(
                feedrate = params.decimalOf('F'),
                s = params.boolOf('S'),
            )
        }
    }
}

/**
 * T3 [F<feedrate>] [S<value>]
 *
 * Select or Report Tool (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/T3.html">MarlinFirmare T3 doc</a>
 */
data class SelectOrReportToolT3(
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `S` */
    val s: Boolean? = null,
) : GRq<SelectOrReportToolT3> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return T(3, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SelectOrReportToolT3> {

        override fun head(): GParameterWord<*> {
            return T(3).head
        }

        override fun decodeParams(params: List<GWord>): SelectOrReportToolT3 {
            return SelectOrReportToolT3(
                feedrate = params.decimalOf('F'),
                s = params.boolOf('S'),
            )
        }
    }
}

/**
 * T4 [F<feedrate>] [S<value>]
 *
 * Select or Report Tool (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/T4.html">MarlinFirmare T4 doc</a>
 */
data class SelectOrReportToolT4(
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `S` */
    val s: Boolean? = null,
) : GRq<SelectOrReportToolT4> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return T(4, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SelectOrReportToolT4> {

        override fun head(): GParameterWord<*> {
            return T(4).head
        }

        override fun decodeParams(params: List<GWord>): SelectOrReportToolT4 {
            return SelectOrReportToolT4(
                feedrate = params.decimalOf('F'),
                s = params.boolOf('S'),
            )
        }
    }
}

/**
 * T5 [F<feedrate>] [S<value>]
 *
 * Select or Report Tool (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/T5.html">MarlinFirmare T5 doc</a>
 */
data class SelectOrReportToolT5(
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `S` */
    val s: Boolean? = null,
) : GRq<SelectOrReportToolT5> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return T(5, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SelectOrReportToolT5> {

        override fun head(): GParameterWord<*> {
            return T(5).head
        }

        override fun decodeParams(params: List<GWord>): SelectOrReportToolT5 {
            return SelectOrReportToolT5(
                feedrate = params.decimalOf('F'),
                s = params.boolOf('S'),
            )
        }
    }
}

/**
 * T6 [F<feedrate>] [S<value>]
 *
 * Select or Report Tool (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/T6.html">MarlinFirmare T6 doc</a>
 */
data class SelectOrReportToolT6(
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `S` */
    val s: Boolean? = null,
) : GRq<SelectOrReportToolT6> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return T(6, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SelectOrReportToolT6> {

        override fun head(): GParameterWord<*> {
            return T(6).head
        }

        override fun decodeParams(params: List<GWord>): SelectOrReportToolT6 {
            return SelectOrReportToolT6(
                feedrate = params.decimalOf('F'),
                s = params.boolOf('S'),
            )
        }
    }
}

/**
 * T7 [F<feedrate>] [S<value>]
 *
 * Select or Report Tool (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/T7.html">MarlinFirmare T7 doc</a>
 */
data class SelectOrReportToolT7(
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `S` */
    val s: Boolean? = null,
) : GRq<SelectOrReportToolT7> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return T(7, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SelectOrReportToolT7> {

        override fun head(): GParameterWord<*> {
            return T(7).head
        }

        override fun decodeParams(params: List<GWord>): SelectOrReportToolT7 {
            return SelectOrReportToolT7(
                feedrate = params.decimalOf('F'),
                s = params.boolOf('S'),
            )
        }
    }
}
