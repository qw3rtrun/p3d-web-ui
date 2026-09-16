// GENERATED FILE - do not edit.
//
// Regenerate with:  python3 tools/marlin/gen_mcommands.py
// Source of truth:  doc/marlin-gcode/commands.json
// Extracted from:   MarlinFirmware/MarlinDocumentation @ 0856d12b0253378bc8d17a246fb09fc8c5437997
// How and why:      tools/marlin/README.md, doc/todos/11-marlin-commands.md
//
// Marlin's `M` commands, one class each, all implementing GRQ and all written the same
// way: `encode()` builds the command with the code/dsl builders, and the companion object
// implements GRQDecoder, so `head()` names the command and `decodeParams` reads one back
// without an instance - `SomeCommand.decode(cmd)`. Every parameter is optional and absent
// by default, so a bare instance encodes to the bare command - `M105` and `M105 T0` are
// different commands and both have to be sayable.
//
// 233 classes over 231 distinct codes and 768 parameter slots. `G`, `M` and `T` are in three
// files only because there are 295 classes in all; MarlinRQ.kt registers every one of them
// and is the single place that sees all three. Generated rather than typed because a
// transposed parameter letter is invisible in review and shows up when a printer answers
// `echo:Unknown command`.

package org.qw3rtrun.p3d.g.marlin

import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.GRQ
import org.qw3rtrun.p3d.g.code.dsl.GRQDecoder
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.text
import org.qw3rtrun.p3d.g.code.dsl.word
import java.math.BigDecimal

/**
 * M0 [S<sec>] [P<ms>]
 *
 * Unconditional stop (motion).
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M0.html">MarlinFirmare M0 doc</a>
 */
data class UnconditionalStopM0(
    /** `S` - sec */
    val sec: Int? = null,
    /** `P` - ms */
    val ms: Int? = null,
) : GRQ<UnconditionalStopM0> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (sec != null) words.add(word('S', sec))
        if (ms != null) words.add(word('P', ms))
        return M(0, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<UnconditionalStopM0> {

        override fun head(): GParameterWord<*> {
            return M(0).head
        }

        override fun decodeParams(params: List<GWord>): UnconditionalStopM0 {
            return UnconditionalStopM0(
                sec = params.intOf('S'),
                ms = params.intOf('P'),
            )
        }
    }
}

/**
 * M1 [S<sec>] [P<ms>]
 *
 * Unconditional stop (motion).
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M1.html">MarlinFirmare M1 doc</a>
 */
data class UnconditionalStopM1(
    /** `S` - sec */
    val sec: Int? = null,
    /** `P` - ms */
    val ms: Int? = null,
) : GRQ<UnconditionalStopM1> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (sec != null) words.add(word('S', sec))
        if (ms != null) words.add(word('P', ms))
        return M(1, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<UnconditionalStopM1> {

        override fun head(): GParameterWord<*> {
            return M(1).head
        }

        override fun decodeParams(params: List<GWord>): UnconditionalStopM1 {
            return UnconditionalStopM1(
                sec = params.intOf('S'),
                ms = params.intOf('P'),
            )
        }
    }
}

/**
 * M3 [S<power>] [O<power>] [I<mode>]
 *
 * Spindle CW / Laser On (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M3.html">MarlinFirmare M3 doc</a>
 */
data class SpindleCWLaserOn(
    /** `S` - power */
    val power: Int? = null,
    /** `O` - power */
    val o: Int? = null,
    /** `I` - mode */
    val mode: Boolean? = null,
) : GRQ<SpindleCWLaserOn> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (power != null) words.add(word('S', power))
        if (o != null) words.add(word('O', o))
        if (mode != null) words.add(word('I', if (mode) 1 else 0))
        return M(3, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SpindleCWLaserOn> {

        override fun head(): GParameterWord<*> {
            return M(3).head
        }

        override fun decodeParams(params: List<GWord>): SpindleCWLaserOn {
            return SpindleCWLaserOn(
                power = params.intOf('S'),
                o = params.intOf('O'),
                mode = params.boolOf('I'),
            )
        }
    }
}

/**
 * M4 [S<power>] [O<power>] [I<mode>]
 *
 * Spindle CCW / Laser On (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M4.html">MarlinFirmare M4 doc</a>
 */
data class SpindleCCWLaserOn(
    /** `S` - power */
    val power: Int? = null,
    /** `O` - power */
    val o: Int? = null,
    /** `I` - mode */
    val mode: Boolean? = null,
) : GRQ<SpindleCCWLaserOn> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (power != null) words.add(word('S', power))
        if (o != null) words.add(word('O', o))
        if (mode != null) words.add(word('I', if (mode) 1 else 0))
        return M(4, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SpindleCCWLaserOn> {

        override fun head(): GParameterWord<*> {
            return M(4).head
        }

        override fun decodeParams(params: List<GWord>): SpindleCCWLaserOn {
            return SpindleCCWLaserOn(
                power = params.intOf('S'),
                o = params.intOf('O'),
                mode = params.boolOf('I'),
            )
        }
    }
}

/**
 * M5
 *
 * Spindle / Laser Off (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M5.html">MarlinFirmare M5 doc</a>
 */
class SpindleLaserOff : GRQ<SpindleLaserOff> {

    override fun encode(): GCommand {
        return M(5)
    }

    override fun equals(other: Any?): Boolean {
        return other is SpindleLaserOff
    }

    override fun hashCode(): Int {
        return 153953
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SpindleLaserOff> {

        override fun head(): GParameterWord<*> {
            return M(5).head
        }

        override fun decodeParams(params: List<GWord>): SpindleLaserOff {
            return SpindleLaserOff()
        }
    }
}

/**
 * M7
 *
 * Coolant Controls (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M7.html">MarlinFirmare M7 doc</a>
 */
class CoolantControlsM7 : GRQ<CoolantControlsM7> {

    override fun encode(): GCommand {
        return M(7)
    }

    override fun equals(other: Any?): Boolean {
        return other is CoolantControlsM7
    }

    override fun hashCode(): Int {
        return 537586
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<CoolantControlsM7> {

        override fun head(): GParameterWord<*> {
            return M(7).head
        }

        override fun decodeParams(params: List<GWord>): CoolantControlsM7 {
            return CoolantControlsM7()
        }
    }
}

/**
 * M8
 *
 * Coolant Controls (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M8.html">MarlinFirmare M8 doc</a>
 */
class CoolantControlsM8 : GRQ<CoolantControlsM8> {

    override fun encode(): GCommand {
        return M(8)
    }

    override fun equals(other: Any?): Boolean {
        return other is CoolantControlsM8
    }

    override fun hashCode(): Int {
        return 136419
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<CoolantControlsM8> {

        override fun head(): GParameterWord<*> {
            return M(8).head
        }

        override fun decodeParams(params: List<GWord>): CoolantControlsM8 {
            return CoolantControlsM8()
        }
    }
}

/**
 * M9
 *
 * Coolant Controls (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M9.html">MarlinFirmare M9 doc</a>
 */
class CoolantControlsM9 : GRQ<CoolantControlsM9> {

    override fun encode(): GCommand {
        return M(9)
    }

    override fun equals(other: Any?): Boolean {
        return other is CoolantControlsM9
    }

    override fun hashCode(): Int {
        return 354229
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<CoolantControlsM9> {

        override fun head(): GParameterWord<*> {
            return M(9).head
        }

        override fun decodeParams(params: List<GWord>): CoolantControlsM9 {
            return CoolantControlsM9()
        }
    }
}

/**
 * M10
 *
 * Vacuum / Blower Control (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M10.html">MarlinFirmare M10 doc</a>
 */
class VacuumBlowerControlM10 : GRQ<VacuumBlowerControlM10> {

    override fun encode(): GCommand {
        return M(10)
    }

    override fun equals(other: Any?): Boolean {
        return other is VacuumBlowerControlM10
    }

    override fun hashCode(): Int {
        return 408713
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<VacuumBlowerControlM10> {

        override fun head(): GParameterWord<*> {
            return M(10).head
        }

        override fun decodeParams(params: List<GWord>): VacuumBlowerControlM10 {
            return VacuumBlowerControlM10()
        }
    }
}

/**
 * M11
 *
 * Vacuum / Blower Control (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M11.html">MarlinFirmare M11 doc</a>
 */
class VacuumBlowerControlM11 : GRQ<VacuumBlowerControlM11> {

    override fun encode(): GCommand {
        return M(11)
    }

    override fun equals(other: Any?): Boolean {
        return other is VacuumBlowerControlM11
    }

    override fun hashCode(): Int {
        return 352607
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<VacuumBlowerControlM11> {

        override fun head(): GParameterWord<*> {
            return M(11).head
        }

        override fun decodeParams(params: List<GWord>): VacuumBlowerControlM11 {
            return VacuumBlowerControlM11()
        }
    }
}

/**
 * M16
 *
 * Expected Printer Check (safety).
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M16.html">MarlinFirmare M16 doc</a>
 */
class ExpectedPrinterCheck : GRQ<ExpectedPrinterCheck> {

    override fun encode(): GCommand {
        return M(16)
    }

    override fun equals(other: Any?): Boolean {
        return other is ExpectedPrinterCheck
    }

    override fun hashCode(): Int {
        return 427281
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ExpectedPrinterCheck> {

        override fun head(): GParameterWord<*> {
            return M(16).head
        }

        override fun decodeParams(params: List<GWord>): ExpectedPrinterCheck {
            return ExpectedPrinterCheck()
        }
    }
}

/**
 * M17 [X] [Y] [Z] [E] [A] [B] [C] [U] [V] [W]
 *
 * Enable Steppers (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M17.html">MarlinFirmare M17 doc</a>
 */
data class EnableSteppers(
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: Boolean = false,
    /** `A` */
    val a: Boolean = false,
    /** `B` */
    val b: Boolean = false,
    /** `C` */
    val c: Boolean = false,
    /** `U` */
    val u: Boolean = false,
    /** `V` */
    val v: Boolean = false,
    /** `W` */
    val w: Boolean = false,
) : GRQ<EnableSteppers> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(10)
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e) words.add(flag('E'))
        if (a) words.add(flag('A'))
        if (b) words.add(flag('B'))
        if (c) words.add(flag('C'))
        if (u) words.add(flag('U'))
        if (v) words.add(flag('V'))
        if (w) words.add(flag('W'))
        return M(17, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<EnableSteppers> {

        override fun head(): GParameterWord<*> {
            return M(17).head
        }

        override fun decodeParams(params: List<GWord>): EnableSteppers {
            return EnableSteppers(
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                a = params.hasWord('A'),
                b = params.hasWord('B'),
                c = params.hasWord('C'),
                u = params.hasWord('U'),
                v = params.hasWord('V'),
                w = params.hasWord('W'),
            )
        }
    }
}

/**
 * M18 [S<seconds>] [X] [Y] [Z] [E] [A] [B] [C] [U] [V] [W]
 *
 * Disable steppers (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M18.html">MarlinFirmare M18 doc</a>
 */
data class DisableSteppersM18(
    /** `S` - seconds */
    val seconds: Int? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: Boolean = false,
    /** `A` */
    val a: Boolean = false,
    /** `B` */
    val b: Boolean = false,
    /** `C` */
    val c: Boolean = false,
    /** `U` */
    val u: Boolean = false,
    /** `V` */
    val v: Boolean = false,
    /** `W` */
    val w: Boolean = false,
) : GRQ<DisableSteppersM18> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(11)
        if (seconds != null) words.add(word('S', seconds))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e) words.add(flag('E'))
        if (a) words.add(flag('A'))
        if (b) words.add(flag('B'))
        if (c) words.add(flag('C'))
        if (u) words.add(flag('U'))
        if (v) words.add(flag('V'))
        if (w) words.add(flag('W'))
        return M(18, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<DisableSteppersM18> {

        override fun head(): GParameterWord<*> {
            return M(18).head
        }

        override fun decodeParams(params: List<GWord>): DisableSteppersM18 {
            return DisableSteppersM18(
                seconds = params.intOf('S'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                a = params.hasWord('A'),
                b = params.hasWord('B'),
                c = params.hasWord('C'),
                u = params.hasWord('U'),
                v = params.hasWord('V'),
                w = params.hasWord('W'),
            )
        }
    }
}

/**
 * M20 [F] [L] [T]
 *
 * List SD Card (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M20.html">MarlinFirmare M20 doc</a>
 */
data class ListSDCard(
    /** `F` */
    val f: Boolean = false,
    /** `L` */
    val l: Boolean = false,
    /** `T` */
    val t: Boolean = false,
) : GRQ<ListSDCard> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (f) words.add(flag('F'))
        if (l) words.add(flag('L'))
        if (t) words.add(flag('T'))
        return M(20, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ListSDCard> {

        override fun head(): GParameterWord<*> {
            return M(20).head
        }

        override fun decodeParams(params: List<GWord>): ListSDCard {
            return ListSDCard(
                f = params.hasWord('F'),
                l = params.hasWord('L'),
                t = params.hasWord('T'),
            )
        }
    }
}

/**
 * M21
 *
 * Init SD card (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M21.html">MarlinFirmare M21 doc</a>
 */
class InitSDCard : GRQ<InitSDCard> {

    override fun encode(): GCommand {
        return M(21)
    }

    override fun equals(other: Any?): Boolean {
        return other is InitSDCard
    }

    override fun hashCode(): Int {
        return 404835
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<InitSDCard> {

        override fun head(): GParameterWord<*> {
            return M(21).head
        }

        override fun decodeParams(params: List<GWord>): InitSDCard {
            return InitSDCard()
        }
    }
}

/**
 * M22
 *
 * Release SD card (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M22.html">MarlinFirmare M22 doc</a>
 */
class ReleaseSDCard : GRQ<ReleaseSDCard> {

    override fun encode(): GCommand {
        return M(22)
    }

    override fun equals(other: Any?): Boolean {
        return other is ReleaseSDCard
    }

    override fun hashCode(): Int {
        return 459804
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ReleaseSDCard> {

        override fun head(): GParameterWord<*> {
            return M(22).head
        }

        override fun decodeParams(params: List<GWord>): ReleaseSDCard {
            return ReleaseSDCard()
        }
    }
}

/**
 * M23
 *
 * Select SD file (sdcard).
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M23.html">MarlinFirmare M23 doc</a>
 */
class SelectSDFile : GRQ<SelectSDFile> {

    override fun encode(): GCommand {
        return M(23)
    }

    override fun equals(other: Any?): Boolean {
        return other is SelectSDFile
    }

    override fun hashCode(): Int {
        return 631038
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SelectSDFile> {

        override fun head(): GParameterWord<*> {
            return M(23).head
        }

        override fun decodeParams(params: List<GWord>): SelectSDFile {
            return SelectSDFile()
        }
    }
}

/**
 * M24 [S<pos>] [T<time>]
 *
 * Start or Resume SD print (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M24.html">MarlinFirmare M24 doc</a>
 */
data class StartOrResumeSDPrint(
    /** `S` - pos */
    val pos: Long? = null,
    /** `T` - time */
    val time: Long? = null,
) : GRQ<StartOrResumeSDPrint> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (pos != null) words.add(word('S', BigDecimal.valueOf(pos)))
        if (time != null) words.add(word('T', BigDecimal.valueOf(time)))
        return M(24, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<StartOrResumeSDPrint> {

        override fun head(): GParameterWord<*> {
            return M(24).head
        }

        override fun decodeParams(params: List<GWord>): StartOrResumeSDPrint {
            return StartOrResumeSDPrint(
                pos = params.longOf('S'),
                time = params.longOf('T'),
            )
        }
    }
}

/**
 * M25
 *
 * Pause SD print (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M25.html">MarlinFirmare M25 doc</a>
 */
class PauseSDPrint : GRQ<PauseSDPrint> {

    override fun encode(): GCommand {
        return M(25)
    }

    override fun equals(other: Any?): Boolean {
        return other is PauseSDPrint
    }

    override fun hashCode(): Int {
        return 726375
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<PauseSDPrint> {

        override fun head(): GParameterWord<*> {
            return M(25).head
        }

        override fun decodeParams(params: List<GWord>): PauseSDPrint {
            return PauseSDPrint()
        }
    }
}

/**
 * M26 [S<pos>]
 *
 * Set SD position (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M26.html">MarlinFirmare M26 doc</a>
 */
data class SetSDPosition(
    /** `S` - pos */
    val pos: Long? = null,
) : GRQ<SetSDPosition> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (pos != null) words.add(word('S', BigDecimal.valueOf(pos)))
        return M(26, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetSDPosition> {

        override fun head(): GParameterWord<*> {
            return M(26).head
        }

        override fun decodeParams(params: List<GWord>): SetSDPosition {
            return SetSDPosition(
                pos = params.longOf('S'),
            )
        }
    }
}

/**
 * M27 [S<seconds>] [C]
 *
 * Report SD print status (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M27.html">MarlinFirmare M27 doc</a>
 */
data class ReportSDPrintStatus(
    /** `S` - seconds */
    val seconds: Int? = null,
    /** `C` */
    val c: Boolean = false,
) : GRQ<ReportSDPrintStatus> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (seconds != null) words.add(word('S', seconds))
        if (c) words.add(flag('C'))
        return M(27, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ReportSDPrintStatus> {

        override fun head(): GParameterWord<*> {
            return M(27).head
        }

        override fun decodeParams(params: List<GWord>): ReportSDPrintStatus {
            return ReportSDPrintStatus(
                seconds = params.intOf('S'),
                c = params.hasWord('C'),
            )
        }
    }
}

/**
 * M28
 *
 * Start SD write (sdcard).
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M28.html">MarlinFirmare M28 doc</a>
 */
class StartSDWrite : GRQ<StartSDWrite> {

    override fun encode(): GCommand {
        return M(28)
    }

    override fun equals(other: Any?): Boolean {
        return other is StartSDWrite
    }

    override fun hashCode(): Int {
        return 25475
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<StartSDWrite> {

        override fun head(): GParameterWord<*> {
            return M(28).head
        }

        override fun decodeParams(params: List<GWord>): StartSDWrite {
            return StartSDWrite()
        }
    }
}

/**
 * M29
 *
 * Stop SD write (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M29.html">MarlinFirmare M29 doc</a>
 */
class StopSDWrite : GRQ<StopSDWrite> {

    override fun encode(): GCommand {
        return M(29)
    }

    override fun equals(other: Any?): Boolean {
        return other is StopSDWrite
    }

    override fun hashCode(): Int {
        return 449368
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<StopSDWrite> {

        override fun head(): GParameterWord<*> {
            return M(29).head
        }

        override fun decodeParams(params: List<GWord>): StopSDWrite {
            return StopSDWrite()
        }
    }
}

/**
 * M30
 *
 * Delete SD file (sdcard).
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M30.html">MarlinFirmare M30 doc</a>
 */
class DeleteSDFile : GRQ<DeleteSDFile> {

    override fun encode(): GCommand {
        return M(30)
    }

    override fun equals(other: Any?): Boolean {
        return other is DeleteSDFile
    }

    override fun hashCode(): Int {
        return 823493
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<DeleteSDFile> {

        override fun head(): GParameterWord<*> {
            return M(30).head
        }

        override fun decodeParams(params: List<GWord>): DeleteSDFile {
            return DeleteSDFile()
        }
    }
}

/**
 * M31
 *
 * Report Print Time (printjob).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M31.html">MarlinFirmare M31 doc</a>
 */
class ReportPrintTime : GRQ<ReportPrintTime> {

    override fun encode(): GCommand {
        return M(31)
    }

    override fun equals(other: Any?): Boolean {
        return other is ReportPrintTime
    }

    override fun hashCode(): Int {
        return 867522
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ReportPrintTime> {

        override fun head(): GParameterWord<*> {
            return M(31).head
        }

        override fun decodeParams(params: List<GWord>): ReportPrintTime {
            return ReportPrintTime()
        }
    }
}

/**
 * M32 [P<value>] [S<filepos>]
 *
 * Select and Start (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M32.html">MarlinFirmare M32 doc</a>
 */
data class SelectAndStart(
    /** `P` */
    val p: Int? = null,
    /** `S` - filepos */
    val filepos: Int? = null,
) : GRQ<SelectAndStart> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (p != null) words.add(word('P', p))
        if (filepos != null) words.add(word('S', filepos))
        return M(32, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SelectAndStart> {

        override fun head(): GParameterWord<*> {
            return M(32).head
        }

        override fun decodeParams(params: List<GWord>): SelectAndStart {
            return SelectAndStart(
                p = params.intOf('P'),
                filepos = params.intOf('S'),
            )
        }
    }
}

/**
 * M33
 *
 * Get Long Path (sdcard).
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M33.html">MarlinFirmare M33 doc</a>
 */
class GetLongPath : GRQ<GetLongPath> {

    override fun encode(): GCommand {
        return M(33)
    }

    override fun equals(other: Any?): Boolean {
        return other is GetLongPath
    }

    override fun hashCode(): Int {
        return 612633
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GetLongPath> {

        override fun head(): GParameterWord<*> {
            return M(33).head
        }

        override fun decodeParams(params: List<GWord>): GetLongPath {
            return GetLongPath()
        }
    }
}

/**
 * M34 [S<value>] [F<value>]
 *
 * SDCard Sorting (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M34.html">MarlinFirmare M34 doc</a>
 */
data class SDCardSorting(
    /** `S` */
    val s: BigDecimal? = null,
    /** `F` */
    val f: Int? = null,
) : GRQ<SDCardSorting> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (s != null) words.add(word('S', s.toPlainString()))
        if (f != null) words.add(word('F', f))
        return M(34, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SDCardSorting> {

        override fun head(): GParameterWord<*> {
            return M(34).head
        }

        override fun decodeParams(params: List<GWord>): SDCardSorting {
            return SDCardSorting(
                s = params.decimalOf('S'),
                f = params.intOf('F'),
            )
        }
    }
}

/**
 * M42 [I<value>] [T<value>] [P<pin>] S<state>
 *
 * Set Pin State (control).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M42.html">MarlinFirmare M42 doc</a>
 */
data class SetPinState(
    /** `I` */
    val i: Boolean? = null,
    /** `T` */
    val t: Int? = null,
    /** `P` - pin */
    val pin: Int? = null,
    /** `S` - state (required) */
    val state: Int? = null,
) : GRQ<SetPinState> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (i != null) words.add(word('I', if (i) 1 else 0))
        if (t != null) words.add(word('T', t))
        if (pin != null) words.add(word('P', pin))
        if (state != null) words.add(word('S', state))
        return M(42, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetPinState> {

        override fun head(): GParameterWord<*> {
            return M(42).head
        }

        override fun decodeParams(params: List<GWord>): SetPinState {
            return SetPinState(
                i = params.boolOf('I'),
                t = params.intOf('T'),
                pin = params.intOf('P'),
                state = params.intOf('S'),
            )
        }
    }
}

/**
 * M43 [P<pin>] [W] [E<value>] [T] [S] [I]
 *
 * Pins Debugging (debug).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M43.html">MarlinFirmare M43 doc</a>
 */
data class PinsDebugging(
    /** `P` - pin */
    val pin: Int? = null,
    /** `W` */
    val w: Boolean = false,
    /** `E` */
    val e: Boolean? = null,
    /** `T` */
    val t: Boolean = false,
    /** `S` */
    val s: Boolean = false,
    /** `I` */
    val i: Boolean = false,
) : GRQ<PinsDebugging> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (pin != null) words.add(word('P', pin))
        if (w) words.add(flag('W'))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        if (t) words.add(flag('T'))
        if (s) words.add(flag('S'))
        if (i) words.add(flag('I'))
        return M(43, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<PinsDebugging> {

        override fun head(): GParameterWord<*> {
            return M(43).head
        }

        override fun decodeParams(params: List<GWord>): PinsDebugging {
            return PinsDebugging(
                pin = params.intOf('P'),
                w = params.hasWord('W'),
                e = params.boolOf('E'),
                t = params.hasWord('T'),
                s = params.hasWord('S'),
                i = params.hasWord('I'),
            )
        }
    }
}

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
) : GRQ<ProbeRepeatabilityTest> {

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

    companion object : GRQDecoder<ProbeRepeatabilityTest> {

        override fun head(): GParameterWord<*> {
            return M(48).head
        }

        override fun decodeParams(params: List<GWord>): ProbeRepeatabilityTest {
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

/**
 * M73 [C<minutes>] [P<percent>] [R<minutes>]
 *
 * Set Print Progress (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M73.html">MarlinFirmare M73 doc</a>
 */
data class SetPrintProgress(
    /** `C` - minutes */
    val minutes: Int? = null,
    /** `P` - percent */
    val percent: Int? = null,
    /** `R` - minutes */
    val r: Int? = null,
) : GRQ<SetPrintProgress> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (minutes != null) words.add(word('C', minutes))
        if (percent != null) words.add(word('P', percent))
        if (r != null) words.add(word('R', r))
        return M(73, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetPrintProgress> {

        override fun head(): GParameterWord<*> {
            return M(73).head
        }

        override fun decodeParams(params: List<GWord>): SetPrintProgress {
            return SetPrintProgress(
                minutes = params.intOf('C'),
                percent = params.intOf('P'),
                r = params.intOf('R'),
            )
        }
    }
}

/**
 * M75
 *
 * Start Print Job Timer (printjob).
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M75.html">MarlinFirmare M75 doc</a>
 */
class StartPrintJobTimer : GRQ<StartPrintJobTimer> {

    override fun encode(): GCommand {
        return M(75)
    }

    override fun equals(other: Any?): Boolean {
        return other is StartPrintJobTimer
    }

    override fun hashCode(): Int {
        return 630488
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<StartPrintJobTimer> {

        override fun head(): GParameterWord<*> {
            return M(75).head
        }

        override fun decodeParams(params: List<GWord>): StartPrintJobTimer {
            return StartPrintJobTimer()
        }
    }
}

/**
 * M76
 *
 * Pause Print Job Timer (printjob).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M76.html">MarlinFirmare M76 doc</a>
 */
class PausePrintJobTimer : GRQ<PausePrintJobTimer> {

    override fun encode(): GCommand {
        return M(76)
    }

    override fun equals(other: Any?): Boolean {
        return other is PausePrintJobTimer
    }

    override fun hashCode(): Int {
        return 14112
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<PausePrintJobTimer> {

        override fun head(): GParameterWord<*> {
            return M(76).head
        }

        override fun decodeParams(params: List<GWord>): PausePrintJobTimer {
            return PausePrintJobTimer()
        }
    }
}

/**
 * M77
 *
 * Stop Print Job Timer (printjob).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M77.html">MarlinFirmare M77 doc</a>
 */
class StopPrintJobTimer : GRQ<StopPrintJobTimer> {

    override fun encode(): GCommand {
        return M(77)
    }

    override fun equals(other: Any?): Boolean {
        return other is StopPrintJobTimer
    }

    override fun hashCode(): Int {
        return 748819
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<StopPrintJobTimer> {

        override fun head(): GParameterWord<*> {
            return M(77).head
        }

        override fun decodeParams(params: List<GWord>): StopPrintJobTimer {
            return StopPrintJobTimer()
        }
    }
}

/**
 * M78
 *
 * Print Job Stats (printjob).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M78.html">MarlinFirmare M78 doc</a>
 */
class PrintJobStats : GRQ<PrintJobStats> {

    override fun encode(): GCommand {
        return M(78)
    }

    override fun equals(other: Any?): Boolean {
        return other is PrintJobStats
    }

    override fun hashCode(): Int {
        return 636404
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<PrintJobStats> {

        override fun head(): GParameterWord<*> {
            return M(78).head
        }

        override fun decodeParams(params: List<GWord>): PrintJobStats {
            return PrintJobStats()
        }
    }
}

/**
 * M80 [S]
 *
 * Power On (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M80.html">MarlinFirmare M80 doc</a>
 */
data class PowerOn(
    /** `S` */
    val s: Boolean = false,
) : GRQ<PowerOn> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (s) words.add(flag('S'))
        return M(80, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<PowerOn> {

        override fun head(): GParameterWord<*> {
            return M(80).head
        }

        override fun decodeParams(params: List<GWord>): PowerOn {
            return PowerOn(
                s = params.hasWord('S'),
            )
        }
    }
}

/**
 * M81
 *
 * Power Off (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M81.html">MarlinFirmare M81 doc</a>
 */
class PowerOff : GRQ<PowerOff> {

    override fun encode(): GCommand {
        return M(81)
    }

    override fun equals(other: Any?): Boolean {
        return other is PowerOff
    }

    override fun hashCode(): Int {
        return 322433
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<PowerOff> {

        override fun head(): GParameterWord<*> {
            return M(81).head
        }

        override fun decodeParams(params: List<GWord>): PowerOff {
            return PowerOff()
        }
    }
}

/**
 * M82
 *
 * E Absolute (units).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M82.html">MarlinFirmare M82 doc</a>
 */
class EAbsolute : GRQ<EAbsolute> {

    override fun encode(): GCommand {
        return M(82)
    }

    override fun equals(other: Any?): Boolean {
        return other is EAbsolute
    }

    override fun hashCode(): Int {
        return 846223
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<EAbsolute> {

        override fun head(): GParameterWord<*> {
            return M(82).head
        }

        override fun decodeParams(params: List<GWord>): EAbsolute {
            return EAbsolute()
        }
    }
}

/**
 * M83
 *
 * E Relative (units).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M83.html">MarlinFirmare M83 doc</a>
 */
class ERelative : GRQ<ERelative> {

    override fun encode(): GCommand {
        return M(83)
    }

    override fun equals(other: Any?): Boolean {
        return other is ERelative
    }

    override fun hashCode(): Int {
        return 735552
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ERelative> {

        override fun head(): GParameterWord<*> {
            return M(83).head
        }

        override fun decodeParams(params: List<GWord>): ERelative {
            return ERelative()
        }
    }
}

/**
 * M84 [S<seconds>] [X] [Y] [Z] [E] [A] [B] [C] [U] [V] [W]
 *
 * Disable steppers (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M84.html">MarlinFirmare M84 doc</a>
 */
data class DisableSteppersM84(
    /** `S` - seconds */
    val seconds: Int? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: Boolean = false,
    /** `A` */
    val a: Boolean = false,
    /** `B` */
    val b: Boolean = false,
    /** `C` */
    val c: Boolean = false,
    /** `U` */
    val u: Boolean = false,
    /** `V` */
    val v: Boolean = false,
    /** `W` */
    val w: Boolean = false,
) : GRQ<DisableSteppersM84> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(11)
        if (seconds != null) words.add(word('S', seconds))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e) words.add(flag('E'))
        if (a) words.add(flag('A'))
        if (b) words.add(flag('B'))
        if (c) words.add(flag('C'))
        if (u) words.add(flag('U'))
        if (v) words.add(flag('V'))
        if (w) words.add(flag('W'))
        return M(84, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<DisableSteppersM84> {

        override fun head(): GParameterWord<*> {
            return M(84).head
        }

        override fun decodeParams(params: List<GWord>): DisableSteppersM84 {
            return DisableSteppersM84(
                seconds = params.intOf('S'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                a = params.hasWord('A'),
                b = params.hasWord('B'),
                c = params.hasWord('C'),
                u = params.hasWord('U'),
                v = params.hasWord('V'),
                w = params.hasWord('W'),
            )
        }
    }
}

/**
 * M85 S<seconds>
 *
 * Inactivity Shutdown (control).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M85.html">MarlinFirmare M85 doc</a>
 */
data class InactivityShutdown(
    /** `S` - seconds (required) */
    val seconds: Int? = null,
) : GRQ<InactivityShutdown> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (seconds != null) words.add(word('S', seconds))
        return M(85, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<InactivityShutdown> {

        override fun head(): GParameterWord<*> {
            return M(85).head
        }

        override fun decodeParams(params: List<GWord>): InactivityShutdown {
            return InactivityShutdown(
                seconds = params.intOf('S'),
            )
        }
    }
}

/**
 * M86 [S<seconds>] [T<temp>] [E<temp>] [B<temp>]
 *
 * Hotend Idle Timeout (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M86.html">MarlinFirmare M86 doc</a>
 */
data class HotendIdleTimeout(
    /** `S` - seconds */
    val seconds: Int? = null,
    /** `T` - temp */
    val temp: Int? = null,
    /** `E` - temp */
    val e: Int? = null,
    /** `B` - temp */
    val b: Int? = null,
) : GRQ<HotendIdleTimeout> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (seconds != null) words.add(word('S', seconds))
        if (temp != null) words.add(word('T', temp))
        if (e != null) words.add(word('E', e))
        if (b != null) words.add(word('B', b))
        return M(86, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<HotendIdleTimeout> {

        override fun head(): GParameterWord<*> {
            return M(86).head
        }

        override fun decodeParams(params: List<GWord>): HotendIdleTimeout {
            return HotendIdleTimeout(
                seconds = params.intOf('S'),
                temp = params.intOf('T'),
                e = params.intOf('E'),
                b = params.intOf('B'),
            )
        }
    }
}

/**
 * M87
 *
 * Disable Hotend Idle Timeout (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M87.html">MarlinFirmare M87 doc</a>
 */
class DisableHotendIdleTimeout : GRQ<DisableHotendIdleTimeout> {

    override fun encode(): GCommand {
        return M(87)
    }

    override fun equals(other: Any?): Boolean {
        return other is DisableHotendIdleTimeout
    }

    override fun hashCode(): Int {
        return 390437
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<DisableHotendIdleTimeout> {

        override fun head(): GParameterWord<*> {
            return M(87).head
        }

        override fun decodeParams(params: List<GWord>): DisableHotendIdleTimeout {
            return DisableHotendIdleTimeout()
        }
    }
}

/**
 * M92 [X<steps>] [Y<steps>] [Z<steps>] [A<steps>] [B<steps>] [C<steps>] [U<steps>] [V<steps>] [W<steps>] [E<steps>] [T<index>]
 *
 * Set Axis Steps-per-unit (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M92.html">MarlinFirmare M92 doc</a>
 */
data class SetAxisStepsPerUnit(
    /** `X` - steps */
    val steps: BigDecimal? = null,
    /** `Y` - steps */
    val y: BigDecimal? = null,
    /** `Z` - steps */
    val z: BigDecimal? = null,
    /** `A` - steps */
    val a: BigDecimal? = null,
    /** `B` - steps */
    val b: BigDecimal? = null,
    /** `C` - steps */
    val c: BigDecimal? = null,
    /** `U` - steps */
    val u: BigDecimal? = null,
    /** `V` - steps */
    val v: BigDecimal? = null,
    /** `W` - steps */
    val w: BigDecimal? = null,
    /** `E` - steps */
    val e: BigDecimal? = null,
    /** `T` - index */
    val index: Int? = null,
) : GRQ<SetAxisStepsPerUnit> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(11)
        if (steps != null) words.add(word('X', steps.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (index != null) words.add(word('T', index))
        return M(92, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetAxisStepsPerUnit> {

        override fun head(): GParameterWord<*> {
            return M(92).head
        }

        override fun decodeParams(params: List<GWord>): SetAxisStepsPerUnit {
            return SetAxisStepsPerUnit(
                steps = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                a = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                u = params.decimalOf('U'),
                v = params.decimalOf('V'),
                w = params.decimalOf('W'),
                e = params.decimalOf('E'),
                index = params.intOf('T'),
            )
        }
    }
}

/**
 * M100 [D] [F] [I] [C<n>]
 *
 * Free Memory (debug).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M100.html">MarlinFirmare M100 doc</a>
 */
data class FreeMemory(
    /** `D` */
    val d: Boolean = false,
    /** `F` */
    val f: Boolean = false,
    /** `I` */
    val i: Boolean = false,
    /** `C` - n */
    val n: Int? = null,
) : GRQ<FreeMemory> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (d) words.add(flag('D'))
        if (f) words.add(flag('F'))
        if (i) words.add(flag('I'))
        if (n != null) words.add(word('C', n))
        return M(100, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FreeMemory> {

        override fun head(): GParameterWord<*> {
            return M(100).head
        }

        override fun decodeParams(params: List<GWord>): FreeMemory {
            return FreeMemory(
                d = params.hasWord('D'),
                f = params.hasWord('F'),
                i = params.hasWord('I'),
                n = params.intOf('C'),
            )
        }
    }
}

/**
 * M102 [S<value>]
 *
 * Configure Bed Distance Sensor (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M102.html">MarlinFirmare M102 doc</a>
 */
data class ConfigureBedDistanceSensor(
    /** `S` */
    val s: BigDecimal? = null,
) : GRQ<ConfigureBedDistanceSensor> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (s != null) words.add(word('S', s.toPlainString()))
        return M(102, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ConfigureBedDistanceSensor> {

        override fun head(): GParameterWord<*> {
            return M(102).head
        }

        override fun decodeParams(params: List<GWord>): ConfigureBedDistanceSensor {
            return ConfigureBedDistanceSensor(
                s = params.decimalOf('S'),
            )
        }
    }
}

/**
 * M104 [I<index>] [S<temp>] [F<factor>] [B<temp>] [T<index>]
 *
 * Set Hotend Temperature (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M104.html">MarlinFirmare M104 doc</a>
 */
data class SetHotendTemperature(
    /** `I` - index */
    val index: Int? = null,
    /** `S` - temp */
    val temp: BigDecimal? = null,
    /** `F` - factor */
    val factor: BigDecimal? = null,
    /** `B` - temp */
    val b: BigDecimal? = null,
    /** `T` - index */
    val t: Int? = null,
) : GRQ<SetHotendTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (index != null) words.add(word('I', index))
        if (temp != null) words.add(word('S', temp.toPlainString()))
        if (factor != null) words.add(word('F', factor.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (t != null) words.add(word('T', t))
        return M(104, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetHotendTemperature> {

        override fun head(): GParameterWord<*> {
            return M(104).head
        }

        override fun decodeParams(params: List<GWord>): SetHotendTemperature {
            return SetHotendTemperature(
                index = params.intOf('I'),
                temp = params.decimalOf('S'),
                factor = params.decimalOf('F'),
                b = params.decimalOf('B'),
                t = params.intOf('T'),
            )
        }
    }
}

/**
 * M105 [R] [T<index>]
 *
 * Report Temperatures (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M105.html">MarlinFirmare M105 doc</a>
 */
data class ReportHotendTemperature(
    /** `R` */
    val r: Boolean = false,
    /** `T` - index */
    val index: Int? = null,
) : GRQ<ReportHotendTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (r) words.add(flag('R'))
        if (index != null) words.add(word('T', index))
        return M(105, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ReportHotendTemperature> {

        override fun head(): GParameterWord<*> {
            return M(105).head
        }

        override fun decodeParams(params: List<GWord>): ReportHotendTemperature {
            return ReportHotendTemperature(
                r = params.hasWord('R'),
                index = params.intOf('T'),
            )
        }
    }
}

/**
 * M106 [I<index>] [S<speed>] [P<index>] [T<value>]
 *
 * Set Fan Speed (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M106.html">MarlinFirmare M106 doc</a>
 */
data class SetFanSpeed(
    /** `I` - index */
    val index: Int? = null,
    /** `S` - speed */
    val speed: Int? = null,
    /** `P` - index */
    val p: Int? = null,
    /** `T` */
    val t: BigDecimal? = null,
) : GRQ<SetFanSpeed> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (index != null) words.add(word('I', index))
        if (speed != null) words.add(word('S', speed))
        if (p != null) words.add(word('P', p))
        if (t != null) words.add(word('T', t.toPlainString()))
        return M(106, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetFanSpeed> {

        override fun head(): GParameterWord<*> {
            return M(106).head
        }

        override fun decodeParams(params: List<GWord>): SetFanSpeed {
            return SetFanSpeed(
                index = params.intOf('I'),
                speed = params.intOf('S'),
                p = params.intOf('P'),
                t = params.decimalOf('T'),
            )
        }
    }
}

/**
 * M107 [P<index>]
 *
 * Fan Off (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M107.html">MarlinFirmare M107 doc</a>
 */
data class FanOff(
    /** `P` - index */
    val index: Int? = null,
) : GRQ<FanOff> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (index != null) words.add(word('P', index))
        return M(107, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FanOff> {

        override fun head(): GParameterWord<*> {
            return M(107).head
        }

        override fun decodeParams(params: List<GWord>): FanOff {
            return FanOff(
                index = params.intOf('P'),
            )
        }
    }
}

/**
 * M108
 *
 * Break and Continue (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M108.html">MarlinFirmare M108 doc</a>
 */
class BreakAndContinue : GRQ<BreakAndContinue> {

    override fun encode(): GCommand {
        return M(108)
    }

    override fun equals(other: Any?): Boolean {
        return other is BreakAndContinue
    }

    override fun hashCode(): Int {
        return 788940
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<BreakAndContinue> {

        override fun head(): GParameterWord<*> {
            return M(108).head
        }

        override fun decodeParams(params: List<GWord>): BreakAndContinue {
            return BreakAndContinue()
        }
    }
}

/**
 * M109 [I<index>] [S<temp>] [R<temp>] [F<factor>] [B<temp>] [T<index>]
 *
 * Wait for Hotend Temperature (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M109.html">MarlinFirmare M109 doc</a>
 */
data class WaitForHotendTemperature(
    /** `I` - index */
    val index: Int? = null,
    /** `S` - temp */
    val temp: BigDecimal? = null,
    /** `R` - temp */
    val r: BigDecimal? = null,
    /** `F` - factor */
    val factor: BigDecimal? = null,
    /** `B` - temp */
    val b: BigDecimal? = null,
    /** `T` - index */
    val t: Int? = null,
) : GRQ<WaitForHotendTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (index != null) words.add(word('I', index))
        if (temp != null) words.add(word('S', temp.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (factor != null) words.add(word('F', factor.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (t != null) words.add(word('T', t))
        return M(109, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<WaitForHotendTemperature> {

        override fun head(): GParameterWord<*> {
            return M(109).head
        }

        override fun decodeParams(params: List<GWord>): WaitForHotendTemperature {
            return WaitForHotendTemperature(
                index = params.intOf('I'),
                temp = params.decimalOf('S'),
                r = params.decimalOf('R'),
                factor = params.decimalOf('F'),
                b = params.decimalOf('B'),
                t = params.intOf('T'),
            )
        }
    }
}

/**
 * M110 N<line>
 *
 * Set / Get Line Number (hosts).
 *
 * Marlin documents `N` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M110.html">MarlinFirmare M110 doc</a>
 */
data class SetGetLineNumber(
    /** `N` - line (required) */
    val line: Int? = null,
) : GRQ<SetGetLineNumber> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (line != null) words.add(word('N', line))
        return M(110, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetGetLineNumber> {

        override fun head(): GParameterWord<*> {
            return M(110).head
        }

        override fun decodeParams(params: List<GWord>): SetGetLineNumber {
            return SetGetLineNumber(
                line = params.intOf('N'),
            )
        }
    }
}

/**
 * M111 [S<flags>]
 *
 * Debug Level (hosts).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M111.html">MarlinFirmare M111 doc</a>
 */
data class DebugLevel(
    /** `S` - flags */
    val flags: Int? = null,
) : GRQ<DebugLevel> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (flags != null) words.add(word('S', flags))
        return M(111, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<DebugLevel> {

        override fun head(): GParameterWord<*> {
            return M(111).head
        }

        override fun decodeParams(params: List<GWord>): DebugLevel {
            return DebugLevel(
                flags = params.intOf('S'),
            )
        }
    }
}

/**
 * M112
 *
 * Full Shutdown (safety).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M112.html">MarlinFirmare M112 doc</a>
 */
class FullShutdown : GRQ<FullShutdown> {

    override fun encode(): GCommand {
        return M(112)
    }

    override fun equals(other: Any?): Boolean {
        return other is FullShutdown
    }

    override fun hashCode(): Int {
        return 216893
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FullShutdown> {

        override fun head(): GParameterWord<*> {
            return M(112).head
        }

        override fun decodeParams(params: List<GWord>): FullShutdown {
            return FullShutdown()
        }
    }
}

/**
 * M113 [S<seconds>]
 *
 * Host Keepalive (hosts).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M113.html">MarlinFirmare M113 doc</a>
 */
data class HostKeepalive(
    /** `S` - seconds */
    val seconds: Int? = null,
) : GRQ<HostKeepalive> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (seconds != null) words.add(word('S', seconds))
        return M(113, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<HostKeepalive> {

        override fun head(): GParameterWord<*> {
            return M(113).head
        }

        override fun decodeParams(params: List<GWord>): HostKeepalive {
            return HostKeepalive(
                seconds = params.intOf('S'),
            )
        }
    }
}

/**
 * M114 [D] [E] [R]
 *
 * Get Current Position (hosts).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M114.html">MarlinFirmare M114 doc</a>
 */
data class GetCurrentPosition(
    /** `D` */
    val d: Boolean = false,
    /** `E` */
    val e: Boolean = false,
    /** `R` */
    val r: Boolean = false,
) : GRQ<GetCurrentPosition> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (d) words.add(flag('D'))
        if (e) words.add(flag('E'))
        if (r) words.add(flag('R'))
        return M(114, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GetCurrentPosition> {

        override fun head(): GParameterWord<*> {
            return M(114).head
        }

        override fun decodeParams(params: List<GWord>): GetCurrentPosition {
            return GetCurrentPosition(
                d = params.hasWord('D'),
                e = params.hasWord('E'),
                r = params.hasWord('R'),
            )
        }
    }
}

/**
 * M115
 *
 * Firmware Info (hosts).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M115.html">MarlinFirmare M115 doc</a>
 */
class FirmwareInfo : GRQ<FirmwareInfo> {

    override fun encode(): GCommand {
        return M(115)
    }

    override fun equals(other: Any?): Boolean {
        return other is FirmwareInfo
    }

    override fun hashCode(): Int {
        return 811662
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FirmwareInfo> {

        override fun head(): GParameterWord<*> {
            return M(115).head
        }

        override fun decodeParams(params: List<GWord>): FirmwareInfo {
            return FirmwareInfo()
        }
    }
}

/**
 * M117
 *
 * Set LCD Message (lcd).
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M117.html">MarlinFirmare M117 doc</a>
 */
class SetLCDMessage : GRQ<SetLCDMessage> {

    override fun encode(): GCommand {
        return M(117)
    }

    override fun equals(other: Any?): Boolean {
        return other is SetLCDMessage
    }

    override fun hashCode(): Int {
        return 164658
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetLCDMessage> {

        override fun head(): GParameterWord<*> {
            return M(117).head
        }

        override fun decodeParams(params: List<GWord>): SetLCDMessage {
            return SetLCDMessage()
        }
    }
}

/**
 * M118 [P<value>]
 *
 * Serial print (hosts).
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M118.html">MarlinFirmare M118 doc</a>
 */
data class SerialPrint(
    /** `P` */
    val p: Int? = null,
) : GRQ<SerialPrint> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (p != null) words.add(word('P', p))
        return M(118, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SerialPrint> {

        override fun head(): GParameterWord<*> {
            return M(118).head
        }

        override fun decodeParams(params: List<GWord>): SerialPrint {
            return SerialPrint(
                p = params.intOf('P'),
            )
        }
    }
}

/**
 * M119
 *
 * Endstop States (debug).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M119.html">MarlinFirmare M119 doc</a>
 */
class EndstopStates : GRQ<EndstopStates> {

    override fun encode(): GCommand {
        return M(119)
    }

    override fun equals(other: Any?): Boolean {
        return other is EndstopStates
    }

    override fun hashCode(): Int {
        return 294704
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<EndstopStates> {

        override fun head(): GParameterWord<*> {
            return M(119).head
        }

        override fun decodeParams(params: List<GWord>): EndstopStates {
            return EndstopStates()
        }
    }
}

/**
 * M120
 *
 * Enable Endstops (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M120.html">MarlinFirmare M120 doc</a>
 */
class EnableEndstops : GRQ<EnableEndstops> {

    override fun encode(): GCommand {
        return M(120)
    }

    override fun equals(other: Any?): Boolean {
        return other is EnableEndstops
    }

    override fun hashCode(): Int {
        return 995130
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<EnableEndstops> {

        override fun head(): GParameterWord<*> {
            return M(120).head
        }

        override fun decodeParams(params: List<GWord>): EnableEndstops {
            return EnableEndstops()
        }
    }
}

/**
 * M121
 *
 * Disable Endstops (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M121.html">MarlinFirmare M121 doc</a>
 */
class DisableEndstops : GRQ<DisableEndstops> {

    override fun encode(): GCommand {
        return M(121)
    }

    override fun equals(other: Any?): Boolean {
        return other is DisableEndstops
    }

    override fun hashCode(): Int {
        return 441877
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<DisableEndstops> {

        override fun head(): GParameterWord<*> {
            return M(121).head
        }

        override fun decodeParams(params: List<GWord>): DisableEndstops {
            return DisableEndstops()
        }
    }
}

/**
 * M122 [I] [X] [Y] [Z] [E] [V] [S<value>] [P<ms>]
 *
 * TMC Debugging (debug).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M122.html">MarlinFirmare M122 doc</a>
 */
data class TMCDebugging(
    /** `I` */
    val i: Boolean = false,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: Boolean = false,
    /** `V` */
    val v: Boolean = false,
    /** `S` */
    val s: Boolean? = null,
    /** `P` - ms */
    val ms: Int? = null,
) : GRQ<TMCDebugging> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (i) words.add(flag('I'))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e) words.add(flag('E'))
        if (v) words.add(flag('V'))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        if (ms != null) words.add(word('P', ms))
        return M(122, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<TMCDebugging> {

        override fun head(): GParameterWord<*> {
            return M(122).head
        }

        override fun decodeParams(params: List<GWord>): TMCDebugging {
            return TMCDebugging(
                i = params.hasWord('I'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                v = params.hasWord('V'),
                s = params.boolOf('S'),
                ms = params.intOf('P'),
            )
        }
    }
}

/**
 * M123
 *
 * Fan Tachometers (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M123.html">MarlinFirmare M123 doc</a>
 */
class FanTachometers : GRQ<FanTachometers> {

    override fun encode(): GCommand {
        return M(123)
    }

    override fun equals(other: Any?): Boolean {
        return other is FanTachometers
    }

    override fun hashCode(): Int {
        return 36749
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FanTachometers> {

        override fun head(): GParameterWord<*> {
            return M(123).head
        }

        override fun decodeParams(params: List<GWord>): FanTachometers {
            return FanTachometers()
        }
    }
}

/**
 * M125 [L<linear>] [X<linear>] [Y<linear>] [Z<linear>] [P<value>]
 *
 * Park Head (nozzle).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M125.html">MarlinFirmare M125 doc</a>
 */
data class ParkHead(
    /** `L` - linear */
    val linear: BigDecimal? = null,
    /** `X` - linear */
    val x: BigDecimal? = null,
    /** `Y` - linear */
    val y: BigDecimal? = null,
    /** `Z` - linear */
    val z: BigDecimal? = null,
    /** `P` */
    val p: Boolean? = null,
) : GRQ<ParkHead> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (linear != null) words.add(word('L', linear.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (p != null) words.add(word('P', if (p) 1 else 0))
        return M(125, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ParkHead> {

        override fun head(): GParameterWord<*> {
            return M(125).head
        }

        override fun decodeParams(params: List<GWord>): ParkHead {
            return ParkHead(
                linear = params.decimalOf('L'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                p = params.boolOf('P'),
            )
        }
    }
}

/**
 * M126 [S<pressure>]
 *
 * Baricuda 1 Open (baricuda).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M126.html">MarlinFirmare M126 doc</a>
 */
data class Baricuda1Open(
    /** `S` - pressure */
    val pressure: Int? = null,
) : GRQ<Baricuda1Open> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (pressure != null) words.add(word('S', pressure))
        return M(126, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<Baricuda1Open> {

        override fun head(): GParameterWord<*> {
            return M(126).head
        }

        override fun decodeParams(params: List<GWord>): Baricuda1Open {
            return Baricuda1Open(
                pressure = params.intOf('S'),
            )
        }
    }
}

/**
 * M127
 *
 * Baricuda 1 Close (baricuda).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M127.html">MarlinFirmare M127 doc</a>
 */
class Baricuda1Close : GRQ<Baricuda1Close> {

    override fun encode(): GCommand {
        return M(127)
    }

    override fun equals(other: Any?): Boolean {
        return other is Baricuda1Close
    }

    override fun hashCode(): Int {
        return 537594
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<Baricuda1Close> {

        override fun head(): GParameterWord<*> {
            return M(127).head
        }

        override fun decodeParams(params: List<GWord>): Baricuda1Close {
            return Baricuda1Close()
        }
    }
}

/**
 * M128 [S<pressure>]
 *
 * Baricuda 2 Open (baricuda).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M128.html">MarlinFirmare M128 doc</a>
 */
data class Baricuda2Open(
    /** `S` - pressure */
    val pressure: Int? = null,
) : GRQ<Baricuda2Open> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (pressure != null) words.add(word('S', pressure))
        return M(128, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<Baricuda2Open> {

        override fun head(): GParameterWord<*> {
            return M(128).head
        }

        override fun decodeParams(params: List<GWord>): Baricuda2Open {
            return Baricuda2Open(
                pressure = params.intOf('S'),
            )
        }
    }
}

/**
 * M129
 *
 * Baricuda 2 Close (baricuda).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M129.html">MarlinFirmare M129 doc</a>
 */
class Baricuda2Close : GRQ<Baricuda2Close> {

    override fun encode(): GCommand {
        return M(129)
    }

    override fun equals(other: Any?): Boolean {
        return other is Baricuda2Close
    }

    override fun hashCode(): Int {
        return 509396
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<Baricuda2Close> {

        override fun head(): GParameterWord<*> {
            return M(129).head
        }

        override fun decodeParams(params: List<GWord>): Baricuda2Close {
            return Baricuda2Close()
        }
    }
}

/**
 * M140 [I<index>] [S<temp>]
 *
 * Set Bed Temperature (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M140.html">MarlinFirmare M140 doc</a>
 */
data class SetBedTemperature(
    /** `I` - index */
    val index: Int? = null,
    /** `S` - temp */
    val temp: BigDecimal? = null,
) : GRQ<SetBedTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (index != null) words.add(word('I', index))
        if (temp != null) words.add(word('S', temp.toPlainString()))
        return M(140, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetBedTemperature> {

        override fun head(): GParameterWord<*> {
            return M(140).head
        }

        override fun decodeParams(params: List<GWord>): SetBedTemperature {
            return SetBedTemperature(
                index = params.intOf('I'),
                temp = params.decimalOf('S'),
            )
        }
    }
}

/**
 * M141 [S<temp>]
 *
 * Set Chamber Temperature (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M141.html">MarlinFirmare M141 doc</a>
 */
data class SetChamberTemperature(
    /** `S` - temp */
    val temp: BigDecimal? = null,
) : GRQ<SetChamberTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (temp != null) words.add(word('S', temp.toPlainString()))
        return M(141, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetChamberTemperature> {

        override fun head(): GParameterWord<*> {
            return M(141).head
        }

        override fun decodeParams(params: List<GWord>): SetChamberTemperature {
            return SetChamberTemperature(
                temp = params.decimalOf('S'),
            )
        }
    }
}

/**
 * M143 [S<temp>]
 *
 * Set Laser Cooler Temperature (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M143.html">MarlinFirmare M143 doc</a>
 */
data class SetLaserCoolerTemperature(
    /** `S` - temp */
    val temp: BigDecimal? = null,
) : GRQ<SetLaserCoolerTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (temp != null) words.add(word('S', temp.toPlainString()))
        return M(143, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetLaserCoolerTemperature> {

        override fun head(): GParameterWord<*> {
            return M(143).head
        }

        override fun decodeParams(params: List<GWord>): SetLaserCoolerTemperature {
            return SetLaserCoolerTemperature(
                temp = params.decimalOf('S'),
            )
        }
    }
}

/**
 * M145 [S<index>] [H<temp>] [B<temp>] [F<speed>]
 *
 * Set Material Preset (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M145.html">MarlinFirmare M145 doc</a>
 */
data class SetMaterialPreset(
    /** `S` - index */
    val index: Int? = null,
    /** `H` - temp */
    val temp: Int? = null,
    /** `B` - temp */
    val b: Int? = null,
    /** `F` - speed */
    val speed: Int? = null,
) : GRQ<SetMaterialPreset> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (index != null) words.add(word('S', index))
        if (temp != null) words.add(word('H', temp))
        if (b != null) words.add(word('B', b))
        if (speed != null) words.add(word('F', speed))
        return M(145, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetMaterialPreset> {

        override fun head(): GParameterWord<*> {
            return M(145).head
        }

        override fun decodeParams(params: List<GWord>): SetMaterialPreset {
            return SetMaterialPreset(
                index = params.intOf('S'),
                temp = params.intOf('H'),
                b = params.intOf('B'),
                speed = params.intOf('F'),
            )
        }
    }
}

/**
 * M149 [C] [F] [K]
 *
 * Set Temperature Units (units).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M149.html">MarlinFirmare M149 doc</a>
 */
data class SetTemperatureUnits(
    /** `C` */
    val c: Boolean = false,
    /** `F` */
    val f: Boolean = false,
    /** `K` */
    val k: Boolean = false,
) : GRQ<SetTemperatureUnits> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (c) words.add(flag('C'))
        if (f) words.add(flag('F'))
        if (k) words.add(flag('K'))
        return M(149, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetTemperatureUnits> {

        override fun head(): GParameterWord<*> {
            return M(149).head
        }

        override fun decodeParams(params: List<GWord>): SetTemperatureUnits {
            return SetTemperatureUnits(
                c = params.hasWord('C'),
                f = params.hasWord('F'),
                k = params.hasWord('K'),
            )
        }
    }
}

/**
 * M150 [R<intensity>] [U<intensity>] [B<intensity>] [W<intensity>] [P<intensity>] [I<pixel>] [S<strip>] [K]
 *
 * Set RGB(W) Color (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M150.html">MarlinFirmare M150 doc</a>
 */
data class SetRGBWColor(
    /** `R` - intensity */
    val intensity: Int? = null,
    /** `U` - intensity */
    val u: Int? = null,
    /** `B` - intensity */
    val b: Int? = null,
    /** `W` - intensity */
    val w: Int? = null,
    /** `P` - intensity */
    val p: Int? = null,
    /** `I` - pixel */
    val pixel: Int? = null,
    /** `S` - strip */
    val strip: Int? = null,
    /** `K` */
    val k: Boolean = false,
) : GRQ<SetRGBWColor> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (intensity != null) words.add(word('R', intensity))
        if (u != null) words.add(word('U', u))
        if (b != null) words.add(word('B', b))
        if (w != null) words.add(word('W', w))
        if (p != null) words.add(word('P', p))
        if (pixel != null) words.add(word('I', pixel))
        if (strip != null) words.add(word('S', strip))
        if (k) words.add(flag('K'))
        return M(150, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetRGBWColor> {

        override fun head(): GParameterWord<*> {
            return M(150).head
        }

        override fun decodeParams(params: List<GWord>): SetRGBWColor {
            return SetRGBWColor(
                intensity = params.intOf('R'),
                u = params.intOf('U'),
                b = params.intOf('B'),
                w = params.intOf('W'),
                p = params.intOf('P'),
                pixel = params.intOf('I'),
                strip = params.intOf('S'),
                k = params.hasWord('K'),
            )
        }
    }
}

/**
 * M154 [S<seconds>]
 *
 * Position Auto-Report (hosts).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M154.html">MarlinFirmare M154 doc</a>
 */
data class PositionAutoReport(
    /** `S` - seconds */
    val seconds: Int? = null,
) : GRQ<PositionAutoReport> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (seconds != null) words.add(word('S', seconds))
        return M(154, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<PositionAutoReport> {

        override fun head(): GParameterWord<*> {
            return M(154).head
        }

        override fun decodeParams(params: List<GWord>): PositionAutoReport {
            return PositionAutoReport(
                seconds = params.intOf('S'),
            )
        }
    }
}

/**
 * M155 [S<seconds>]
 *
 * Temperature Auto-Report (hosts).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M155.html">MarlinFirmare M155 doc</a>
 */
data class TemperatureAutoReport(
    /** `S` - seconds */
    val seconds: Int? = null,
) : GRQ<TemperatureAutoReport> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (seconds != null) words.add(word('S', seconds))
        return M(155, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<TemperatureAutoReport> {

        override fun head(): GParameterWord<*> {
            return M(155).head
        }

        override fun decodeParams(params: List<GWord>): TemperatureAutoReport {
            return TemperatureAutoReport(
                seconds = params.intOf('S'),
            )
        }
    }
}

/**
 * M163 [S<index>] [P<factor>]
 *
 * Set Mix Factor (mixing).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M163.html">MarlinFirmare M163 doc</a>
 */
data class SetMixFactor(
    /** `S` - index */
    val index: Int? = null,
    /** `P` - factor */
    val factor: BigDecimal? = null,
) : GRQ<SetMixFactor> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (index != null) words.add(word('S', index))
        if (factor != null) words.add(word('P', factor.toPlainString()))
        return M(163, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetMixFactor> {

        override fun head(): GParameterWord<*> {
            return M(163).head
        }

        override fun decodeParams(params: List<GWord>): SetMixFactor {
            return SetMixFactor(
                index = params.intOf('S'),
                factor = params.decimalOf('P'),
            )
        }
    }
}

/**
 * M164 S<index>
 *
 * Save Mix (mixing).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M164.html">MarlinFirmare M164 doc</a>
 */
data class SaveMix(
    /** `S` - index (required) */
    val index: Int? = null,
) : GRQ<SaveMix> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (index != null) words.add(word('S', index))
        return M(164, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SaveMix> {

        override fun head(): GParameterWord<*> {
            return M(164).head
        }

        override fun decodeParams(params: List<GWord>): SaveMix {
            return SaveMix(
                index = params.intOf('S'),
            )
        }
    }
}

/**
 * M165 [A<factor>] [B<factor>] [C<factor>] [D<factor>] [H<factor>] [I<factor>]
 *
 * Set Mix (mixing).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M165.html">MarlinFirmare M165 doc</a>
 */
data class SetMix(
    /** `A` - factor */
    val factor: BigDecimal? = null,
    /** `B` - factor */
    val b: BigDecimal? = null,
    /** `C` - factor */
    val c: BigDecimal? = null,
    /** `D` - factor */
    val d: BigDecimal? = null,
    /** `H` - factor */
    val h: BigDecimal? = null,
    /** `I` - factor */
    val i: BigDecimal? = null,
) : GRQ<SetMix> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (factor != null) words.add(word('A', factor.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (d != null) words.add(word('D', d.toPlainString()))
        if (h != null) words.add(word('H', h.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        return M(165, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetMix> {

        override fun head(): GParameterWord<*> {
            return M(165).head
        }

        override fun decodeParams(params: List<GWord>): SetMix {
            return SetMix(
                factor = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                d = params.decimalOf('D'),
                h = params.decimalOf('H'),
                i = params.decimalOf('I'),
            )
        }
    }
}

/**
 * M166 A<linear> Z<linear> I<index> J<index> [S<enable>] [T<index>]
 *
 * Gradient Mix (mixing).
 *
 * Marlin documents `A`, `Z`, `I`, `J` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M166.html">MarlinFirmare M166 doc</a>
 */
data class GradientMix(
    /** `A` - linear (required) */
    val linear: BigDecimal? = null,
    /** `Z` - linear (required) */
    val z: BigDecimal? = null,
    /** `I` - index (required) */
    val index: Int? = null,
    /** `J` - index (required) */
    val j: Int? = null,
    /** `S` - enable */
    val enable: Boolean? = null,
    /** `T` - index */
    val t: Int? = null,
) : GRQ<GradientMix> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (linear != null) words.add(word('A', linear.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (index != null) words.add(word('I', index))
        if (j != null) words.add(word('J', j))
        if (enable != null) words.add(word('S', if (enable) 1 else 0))
        if (t != null) words.add(word('T', t))
        return M(166, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GradientMix> {

        override fun head(): GParameterWord<*> {
            return M(166).head
        }

        override fun decodeParams(params: List<GWord>): GradientMix {
            return GradientMix(
                linear = params.decimalOf('A'),
                z = params.decimalOf('Z'),
                index = params.intOf('I'),
                j = params.intOf('J'),
                enable = params.boolOf('S'),
                t = params.intOf('T'),
            )
        }
    }
}

/**
 * M190 [I<index>] [S<temp>] [R<temp>] [T<seconds>]
 *
 * Wait for Bed Temperature (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M190.html">MarlinFirmare M190 doc</a>
 */
data class WaitForBedTemperature(
    /** `I` - index */
    val index: Int? = null,
    /** `S` - temp */
    val temp: BigDecimal? = null,
    /** `R` - temp */
    val r: BigDecimal? = null,
    /** `T` - seconds */
    val seconds: Int? = null,
) : GRQ<WaitForBedTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (index != null) words.add(word('I', index))
        if (temp != null) words.add(word('S', temp.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (seconds != null) words.add(word('T', seconds))
        return M(190, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<WaitForBedTemperature> {

        override fun head(): GParameterWord<*> {
            return M(190).head
        }

        override fun decodeParams(params: List<GWord>): WaitForBedTemperature {
            return WaitForBedTemperature(
                index = params.intOf('I'),
                temp = params.decimalOf('S'),
                r = params.decimalOf('R'),
                seconds = params.intOf('T'),
            )
        }
    }
}

/**
 * M191 [S<temp>] [R<temp>]
 *
 * Wait for Chamber Temperature (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M191.html">MarlinFirmare M191 doc</a>
 */
data class WaitForChamberTemperature(
    /** `S` - temp */
    val temp: BigDecimal? = null,
    /** `R` - temp */
    val r: BigDecimal? = null,
) : GRQ<WaitForChamberTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (temp != null) words.add(word('S', temp.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        return M(191, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<WaitForChamberTemperature> {

        override fun head(): GParameterWord<*> {
            return M(191).head
        }

        override fun decodeParams(params: List<GWord>): WaitForChamberTemperature {
            return WaitForChamberTemperature(
                temp = params.decimalOf('S'),
                r = params.decimalOf('R'),
            )
        }
    }
}

/**
 * M192 [R<temp>] [S<temp>]
 *
 * Wait for Probe temperature (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M192.html">MarlinFirmare M192 doc</a>
 */
data class WaitForProbeTemperature(
    /** `R` - temp */
    val temp: Int? = null,
    /** `S` - temp */
    val s: Int? = null,
) : GRQ<WaitForProbeTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (temp != null) words.add(word('R', temp))
        if (s != null) words.add(word('S', s))
        return M(192, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<WaitForProbeTemperature> {

        override fun head(): GParameterWord<*> {
            return M(192).head
        }

        override fun decodeParams(params: List<GWord>): WaitForProbeTemperature {
            return WaitForProbeTemperature(
                temp = params.intOf('R'),
                s = params.intOf('S'),
            )
        }
    }
}

/**
 * M193 [S<temp>]
 *
 * Wait For Laser Cooler Temperature (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M193.html">MarlinFirmare M193 doc</a>
 */
data class WaitForLaserCoolerTemperature(
    /** `S` - temp */
    val temp: BigDecimal? = null,
) : GRQ<WaitForLaserCoolerTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (temp != null) words.add(word('S', temp.toPlainString()))
        return M(193, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<WaitForLaserCoolerTemperature> {

        override fun head(): GParameterWord<*> {
            return M(193).head
        }

        override fun decodeParams(params: List<GWord>): WaitForLaserCoolerTemperature {
            return WaitForLaserCoolerTemperature(
                temp = params.decimalOf('S'),
            )
        }
    }
}

/**
 * M200 [D<diameter>] [L<volume>] [S<value>] [T<index>]
 *
 * Volumetric Extrusion Diameter (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M200.html">MarlinFirmare M200 doc</a>
 */
data class VolumetricExtrusionDiameter(
    /** `D` - diameter */
    val diameter: BigDecimal? = null,
    /** `L` - volume */
    val volume: BigDecimal? = null,
    /** `S` */
    val s: Int? = null,
    /** `T` - index */
    val index: Int? = null,
) : GRQ<VolumetricExtrusionDiameter> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (diameter != null) words.add(word('D', diameter.toPlainString()))
        if (volume != null) words.add(word('L', volume.toPlainString()))
        if (s != null) words.add(word('S', s))
        if (index != null) words.add(word('T', index))
        return M(200, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<VolumetricExtrusionDiameter> {

        override fun head(): GParameterWord<*> {
            return M(200).head
        }

        override fun decodeParams(params: List<GWord>): VolumetricExtrusionDiameter {
            return VolumetricExtrusionDiameter(
                diameter = params.decimalOf('D'),
                volume = params.decimalOf('L'),
                s = params.intOf('S'),
                index = params.intOf('T'),
            )
        }
    }
}

/**
 * M201 [X<accel>] [Y<accel>] [Z<accel>] [E<accel>] [T<index>] [F<value>] [S<percent>]
 *
 * Print / Travel Move Limits (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M201.html">MarlinFirmare M201 doc</a>
 */
data class PrintTravelMoveLimits(
    /** `X` - accel */
    val accel: BigDecimal? = null,
    /** `Y` - accel */
    val y: BigDecimal? = null,
    /** `Z` - accel */
    val z: BigDecimal? = null,
    /** `E` - accel */
    val e: BigDecimal? = null,
    /** `T` - index */
    val index: Int? = null,
    /** `F` */
    val f: Int? = null,
    /** `S` - percent */
    val percent: BigDecimal? = null,
) : GRQ<PrintTravelMoveLimits> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(7)
        if (accel != null) words.add(word('X', accel.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (index != null) words.add(word('T', index))
        if (f != null) words.add(word('F', f))
        if (percent != null) words.add(word('S', percent.toPlainString()))
        return M(201, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<PrintTravelMoveLimits> {

        override fun head(): GParameterWord<*> {
            return M(201).head
        }

        override fun decodeParams(params: List<GWord>): PrintTravelMoveLimits {
            return PrintTravelMoveLimits(
                accel = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                e = params.decimalOf('E'),
                index = params.intOf('T'),
                f = params.intOf('F'),
                percent = params.decimalOf('S'),
            )
        }
    }
}

/**
 * M203 [X<value>] [Y<value>] [Z<value>] [E<value>] [T<index>]
 *
 * Set Max Feedrate (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M203.html">MarlinFirmare M203 doc</a>
 */
data class SetMaxFeedrate(
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
    /** `E` */
    val e: BigDecimal? = null,
    /** `T` - index */
    val index: Int? = null,
) : GRQ<SetMaxFeedrate> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (index != null) words.add(word('T', index))
        return M(203, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetMaxFeedrate> {

        override fun head(): GParameterWord<*> {
            return M(203).head
        }

        override fun decodeParams(params: List<GWord>): SetMaxFeedrate {
            return SetMaxFeedrate(
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                e = params.decimalOf('E'),
                index = params.intOf('T'),
            )
        }
    }
}

/**
 * M204 [P<accel>] [R<accel>] [T<accel>] [S<accel>]
 *
 * Set Starting Acceleration (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M204.html">MarlinFirmare M204 doc</a>
 */
data class SetStartingAcceleration(
    /** `P` - accel */
    val accel: BigDecimal? = null,
    /** `R` - accel */
    val r: BigDecimal? = null,
    /** `T` - accel */
    val t: BigDecimal? = null,
    /** `S` - accel */
    val s: BigDecimal? = null,
) : GRQ<SetStartingAcceleration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (accel != null) words.add(word('P', accel.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (t != null) words.add(word('T', t.toPlainString()))
        if (s != null) words.add(word('S', s.toPlainString()))
        return M(204, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetStartingAcceleration> {

        override fun head(): GParameterWord<*> {
            return M(204).head
        }

        override fun decodeParams(params: List<GWord>): SetStartingAcceleration {
            return SetStartingAcceleration(
                accel = params.decimalOf('P'),
                r = params.decimalOf('R'),
                t = params.decimalOf('T'),
                s = params.decimalOf('S'),
            )
        }
    }
}

/**
 * M205 [X<jerk>] [Y<jerk>] [Z<jerk>] [E<jerk>] [B<value>] [S<value>] [T<value>] [J<deviation>]
 *
 * Set Advanced Settings (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M205.html">MarlinFirmare M205 doc</a>
 */
data class SetAdvancedSettings(
    /** `X` - jerk */
    val jerk: BigDecimal? = null,
    /** `Y` - jerk */
    val y: BigDecimal? = null,
    /** `Z` - jerk */
    val z: BigDecimal? = null,
    /** `E` - jerk */
    val e: BigDecimal? = null,
    /** `B` */
    val b: Int? = null,
    /** `S` */
    val s: BigDecimal? = null,
    /** `T` */
    val t: BigDecimal? = null,
    /** `J` - deviation */
    val deviation: BigDecimal? = null,
) : GRQ<SetAdvancedSettings> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (jerk != null) words.add(word('X', jerk.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (b != null) words.add(word('B', b))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (t != null) words.add(word('T', t.toPlainString()))
        if (deviation != null) words.add(word('J', deviation.toPlainString()))
        return M(205, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetAdvancedSettings> {

        override fun head(): GParameterWord<*> {
            return M(205).head
        }

        override fun decodeParams(params: List<GWord>): SetAdvancedSettings {
            return SetAdvancedSettings(
                jerk = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                e = params.decimalOf('E'),
                b = params.intOf('B'),
                s = params.decimalOf('S'),
                t = params.decimalOf('T'),
                deviation = params.decimalOf('J'),
            )
        }
    }
}

/**
 * M206 [P<offset>] [T<offset>] [X<offset>] [Y<offset>] [Z<offset>] [A<offset>] [B<offset>] [C<offset>] [U<offset>] [V<offset>] [W<offset>]
 *
 * Set Home Offsets (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M206.html">MarlinFirmare M206 doc</a>
 */
data class SetHomeOffsets(
    /** `P` - offset */
    val offset: BigDecimal? = null,
    /** `T` - offset */
    val t: BigDecimal? = null,
    /** `X` - offset */
    val x: BigDecimal? = null,
    /** `Y` - offset */
    val y: BigDecimal? = null,
    /** `Z` - offset */
    val z: BigDecimal? = null,
    /** `A` - offset */
    val a: BigDecimal? = null,
    /** `B` - offset */
    val b: BigDecimal? = null,
    /** `C` - offset */
    val c: BigDecimal? = null,
    /** `U` - offset */
    val u: BigDecimal? = null,
    /** `V` - offset */
    val v: BigDecimal? = null,
    /** `W` - offset */
    val w: BigDecimal? = null,
) : GRQ<SetHomeOffsets> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(11)
        if (offset != null) words.add(word('P', offset.toPlainString()))
        if (t != null) words.add(word('T', t.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        return M(206, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetHomeOffsets> {

        override fun head(): GParameterWord<*> {
            return M(206).head
        }

        override fun decodeParams(params: List<GWord>): SetHomeOffsets {
            return SetHomeOffsets(
                offset = params.decimalOf('P'),
                t = params.decimalOf('T'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                a = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                u = params.decimalOf('U'),
                v = params.decimalOf('V'),
                w = params.decimalOf('W'),
            )
        }
    }
}

/**
 * M207 [S<length>] [W<length>] [F<feedrate>] [Z<length>]
 *
 * Firmware Retraction Settings (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M207.html">MarlinFirmare M207 doc</a>
 */
data class FirmwareRetractionSettings(
    /** `S` - length */
    val length: BigDecimal? = null,
    /** `W` - length */
    val w: BigDecimal? = null,
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `Z` - length */
    val z: BigDecimal? = null,
) : GRQ<FirmwareRetractionSettings> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (length != null) words.add(word('S', length.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(207, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FirmwareRetractionSettings> {

        override fun head(): GParameterWord<*> {
            return M(207).head
        }

        override fun decodeParams(params: List<GWord>): FirmwareRetractionSettings {
            return FirmwareRetractionSettings(
                length = params.decimalOf('S'),
                w = params.decimalOf('W'),
                feedrate = params.decimalOf('F'),
                z = params.decimalOf('Z'),
            )
        }
    }
}

/**
 * M208 [S<length>] [W<length>] [F<feedrate>] [R<feedrate>]
 *
 * Firmware Recover Settings (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M208.html">MarlinFirmare M208 doc</a>
 */
data class FirmwareRecoverSettings(
    /** `S` - length */
    val length: BigDecimal? = null,
    /** `W` - length */
    val w: BigDecimal? = null,
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `R` - feedrate */
    val r: BigDecimal? = null,
) : GRQ<FirmwareRecoverSettings> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (length != null) words.add(word('S', length.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        return M(208, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FirmwareRecoverSettings> {

        override fun head(): GParameterWord<*> {
            return M(208).head
        }

        override fun decodeParams(params: List<GWord>): FirmwareRecoverSettings {
            return FirmwareRecoverSettings(
                length = params.decimalOf('S'),
                w = params.decimalOf('W'),
                feedrate = params.decimalOf('F'),
                r = params.decimalOf('R'),
            )
        }
    }
}

/**
 * M209 S<value>
 *
 * Set Auto Retract (motion).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M209.html">MarlinFirmare M209 doc</a>
 */
data class SetAutoRetract(
    /** `S` (required) */
    val s: Boolean? = null,
) : GRQ<SetAutoRetract> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return M(209, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetAutoRetract> {

        override fun head(): GParameterWord<*> {
            return M(209).head
        }

        override fun decodeParams(params: List<GWord>): SetAutoRetract {
            return SetAutoRetract(
                s = params.boolOf('S'),
            )
        }
    }
}

/**
 * M210 [X<feedrate>] [Y<feedrate>] [Z<feedrate>] [A<feedrate>] [B<feedrate>] [C<feedrate>] [U<feedrate>] [V<feedrate>] [W<feedrate>]
 *
 * Homing Feedrate (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M210.html">MarlinFirmare M210 doc</a>
 */
data class HomingFeedrate(
    /** `X` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `Y` - feedrate */
    val y: BigDecimal? = null,
    /** `Z` - feedrate */
    val z: BigDecimal? = null,
    /** `A` - feedrate */
    val a: BigDecimal? = null,
    /** `B` - feedrate */
    val b: BigDecimal? = null,
    /** `C` - feedrate */
    val c: BigDecimal? = null,
    /** `U` - feedrate */
    val u: BigDecimal? = null,
    /** `V` - feedrate */
    val v: BigDecimal? = null,
    /** `W` - feedrate */
    val w: BigDecimal? = null,
) : GRQ<HomingFeedrate> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(9)
        if (feedrate != null) words.add(word('X', feedrate.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        return M(210, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<HomingFeedrate> {

        override fun head(): GParameterWord<*> {
            return M(210).head
        }

        override fun decodeParams(params: List<GWord>): HomingFeedrate {
            return HomingFeedrate(
                feedrate = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                a = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                u = params.decimalOf('U'),
                v = params.decimalOf('V'),
                w = params.decimalOf('W'),
            )
        }
    }
}

/**
 * M211 [S<value>]
 *
 * Software Endstops (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M211.html">MarlinFirmare M211 doc</a>
 */
data class SoftwareEndstops(
    /** `S` */
    val s: Boolean? = null,
) : GRQ<SoftwareEndstops> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return M(211, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SoftwareEndstops> {

        override fun head(): GParameterWord<*> {
            return M(211).head
        }

        override fun decodeParams(params: List<GWord>): SoftwareEndstops {
            return SoftwareEndstops(
                s = params.boolOf('S'),
            )
        }
    }
}

/**
 * M217 [Q] [S<linear>] [B<linear>] [E<linear>] [P<feedrate>] [R<feedrate>] [U<linear>] [F<linear>] [G<linear>] [A<linear>] [L<linear>] [W<linear>] [X<linear>] [Y<linear>] [V<linear>] [Z<feedrate>] [I<linear>] [J<linear>] [K<linear>] [C<linear>] [H<linear>] [O<linear>]
 *
 * Filament swap parameters (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M217.html">MarlinFirmare M217 doc</a>
 */
data class FilamentSwapParameters(
    /** `Q` */
    val q: Boolean = false,
    /** `S` - linear */
    val linear: BigDecimal? = null,
    /** `B` - linear */
    val b: BigDecimal? = null,
    /** `E` - linear */
    val e: BigDecimal? = null,
    /** `P` - feedrate */
    val feedrate: Int? = null,
    /** `R` - feedrate */
    val r: Int? = null,
    /** `U` - linear */
    val u: Int? = null,
    /** `F` - linear */
    val f: Int? = null,
    /** `G` - linear */
    val g: Int? = null,
    /** `A` - linear */
    val a: Int? = null,
    /** `L` - linear */
    val l: Int? = null,
    /** `W` - linear */
    val w: Int? = null,
    /** `X` - linear */
    val x: BigDecimal? = null,
    /** `Y` - linear */
    val y: BigDecimal? = null,
    /** `V` - linear */
    val v: Int? = null,
    /** `Z` - feedrate */
    val z: Int? = null,
    /** `I` - linear */
    val i: BigDecimal? = null,
    /** `J` - linear */
    val j: BigDecimal? = null,
    /** `K` - linear */
    val k: BigDecimal? = null,
    /** `C` - linear */
    val c: BigDecimal? = null,
    /** `H` - linear */
    val h: BigDecimal? = null,
    /** `O` - linear */
    val o: BigDecimal? = null,
) : GRQ<FilamentSwapParameters> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(22)
        if (q) words.add(flag('Q'))
        if (linear != null) words.add(word('S', linear.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (feedrate != null) words.add(word('P', feedrate))
        if (r != null) words.add(word('R', r))
        if (u != null) words.add(word('U', u))
        if (f != null) words.add(word('F', f))
        if (g != null) words.add(word('G', g))
        if (a != null) words.add(word('A', a))
        if (l != null) words.add(word('L', l))
        if (w != null) words.add(word('W', w))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (v != null) words.add(word('V', v))
        if (z != null) words.add(word('Z', z))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (k != null) words.add(word('K', k.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (h != null) words.add(word('H', h.toPlainString()))
        if (o != null) words.add(word('O', o.toPlainString()))
        return M(217, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FilamentSwapParameters> {

        override fun head(): GParameterWord<*> {
            return M(217).head
        }

        override fun decodeParams(params: List<GWord>): FilamentSwapParameters {
            return FilamentSwapParameters(
                q = params.hasWord('Q'),
                linear = params.decimalOf('S'),
                b = params.decimalOf('B'),
                e = params.decimalOf('E'),
                feedrate = params.intOf('P'),
                r = params.intOf('R'),
                u = params.intOf('U'),
                f = params.intOf('F'),
                g = params.intOf('G'),
                a = params.intOf('A'),
                l = params.intOf('L'),
                w = params.intOf('W'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                v = params.intOf('V'),
                z = params.intOf('Z'),
                i = params.decimalOf('I'),
                j = params.decimalOf('J'),
                k = params.decimalOf('K'),
                c = params.decimalOf('C'),
                h = params.decimalOf('H'),
                o = params.decimalOf('O'),
            )
        }
    }
}

/**
 * M218 [T<index>] [X<offset>] [Y<offset>] [Z<offset>]
 *
 * Set Hotend Offset (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M218.html">MarlinFirmare M218 doc</a>
 */
data class SetHotendOffset(
    /** `T` - index */
    val index: Int? = null,
    /** `X` - offset */
    val offset: BigDecimal? = null,
    /** `Y` - offset */
    val y: BigDecimal? = null,
    /** `Z` - offset */
    val z: BigDecimal? = null,
) : GRQ<SetHotendOffset> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (index != null) words.add(word('T', index))
        if (offset != null) words.add(word('X', offset.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(218, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetHotendOffset> {

        override fun head(): GParameterWord<*> {
            return M(218).head
        }

        override fun decodeParams(params: List<GWord>): SetHotendOffset {
            return SetHotendOffset(
                index = params.intOf('T'),
                offset = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
            )
        }
    }
}

/**
 * M220 [S<percent>] [B] [R]
 *
 * Set Feedrate Percentage (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M220.html">MarlinFirmare M220 doc</a>
 */
data class SetFeedratePercentage(
    /** `S` - percent */
    val percent: Int? = null,
    /** `B` */
    val b: Boolean = false,
    /** `R` */
    val r: Boolean = false,
) : GRQ<SetFeedratePercentage> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (percent != null) words.add(word('S', percent))
        if (b) words.add(flag('B'))
        if (r) words.add(flag('R'))
        return M(220, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetFeedratePercentage> {

        override fun head(): GParameterWord<*> {
            return M(220).head
        }

        override fun decodeParams(params: List<GWord>): SetFeedratePercentage {
            return SetFeedratePercentage(
                percent = params.intOf('S'),
                b = params.hasWord('B'),
                r = params.hasWord('R'),
            )
        }
    }
}

/**
 * M221 S<percent> [T<index>]
 *
 * Set Flow Percentage (motion).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M221.html">MarlinFirmare M221 doc</a>
 */
data class SetFlowPercentage(
    /** `S` - percent (required) */
    val percent: Int? = null,
    /** `T` - index */
    val index: Int? = null,
) : GRQ<SetFlowPercentage> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (percent != null) words.add(word('S', percent))
        if (index != null) words.add(word('T', index))
        return M(221, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetFlowPercentage> {

        override fun head(): GParameterWord<*> {
            return M(221).head
        }

        override fun decodeParams(params: List<GWord>): SetFlowPercentage {
            return SetFlowPercentage(
                percent = params.intOf('S'),
                index = params.intOf('T'),
            )
        }
    }
}

/**
 * M226 P<pin> [S<state>]
 *
 * Wait for Pin State (control).
 *
 * Marlin documents `P` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M226.html">MarlinFirmare M226 doc</a>
 */
data class WaitForPinState(
    /** `P` - pin (required) */
    val pin: Int? = null,
    /** `S` - state */
    val state: Int? = null,
) : GRQ<WaitForPinState> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (pin != null) words.add(word('P', pin))
        if (state != null) words.add(word('S', state))
        return M(226, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<WaitForPinState> {

        override fun head(): GParameterWord<*> {
            return M(226).head
        }

        override fun decodeParams(params: List<GWord>): WaitForPinState {
            return WaitForPinState(
                pin = params.intOf('P'),
                state = params.intOf('S'),
            )
        }
    }
}

/**
 * M240 [A<offset>] [B<offset>] [D<ms>] [F<feedrate>] [I<pos>] [J<pos>] [P<ms>] [R<length>] [S<feedrate>] [X<pos>] [Y<pos>] [Z<length>]
 *
 * Trigger Camera (extras).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M240.html">MarlinFirmare M240 doc</a>
 */
data class TriggerCamera(
    /** `A` - offset */
    val offset: BigDecimal? = null,
    /** `B` - offset */
    val b: BigDecimal? = null,
    /** `D` - ms */
    val ms: Int? = null,
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `I` - pos */
    val pos: BigDecimal? = null,
    /** `J` - pos */
    val j: BigDecimal? = null,
    /** `P` - ms */
    val p: Int? = null,
    /** `R` - length */
    val length: BigDecimal? = null,
    /** `S` - feedrate */
    val s: BigDecimal? = null,
    /** `X` - pos */
    val x: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - length */
    val z: BigDecimal? = null,
) : GRQ<TriggerCamera> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(12)
        if (offset != null) words.add(word('A', offset.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (ms != null) words.add(word('D', ms))
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (pos != null) words.add(word('I', pos.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (p != null) words.add(word('P', p))
        if (length != null) words.add(word('R', length.toPlainString()))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(240, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<TriggerCamera> {

        override fun head(): GParameterWord<*> {
            return M(240).head
        }

        override fun decodeParams(params: List<GWord>): TriggerCamera {
            return TriggerCamera(
                offset = params.decimalOf('A'),
                b = params.decimalOf('B'),
                ms = params.intOf('D'),
                feedrate = params.decimalOf('F'),
                pos = params.decimalOf('I'),
                j = params.decimalOf('J'),
                p = params.intOf('P'),
                length = params.decimalOf('R'),
                s = params.decimalOf('S'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
            )
        }
    }
}

/**
 * M250 [C<contrast>]
 *
 * LCD Contrast (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M250.html">MarlinFirmare M250 doc</a>
 */
data class LCDContrast(
    /** `C` - contrast */
    val contrast: Int? = null,
) : GRQ<LCDContrast> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (contrast != null) words.add(word('C', contrast))
        return M(250, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<LCDContrast> {

        override fun head(): GParameterWord<*> {
            return M(250).head
        }

        override fun decodeParams(params: List<GWord>): LCDContrast {
            return LCDContrast(
                contrast = params.intOf('C'),
            )
        }
    }
}

/**
 * M255 [S<minutes>]
 *
 * LCD Sleep/Backlight Timeout (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M255.html">MarlinFirmare M255 doc</a>
 */
data class LCDSleepBacklightTimeout(
    /** `S` - minutes */
    val minutes: Int? = null,
) : GRQ<LCDSleepBacklightTimeout> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (minutes != null) words.add(word('S', minutes))
        return M(255, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<LCDSleepBacklightTimeout> {

        override fun head(): GParameterWord<*> {
            return M(255).head
        }

        override fun decodeParams(params: List<GWord>): LCDSleepBacklightTimeout {
            return LCDSleepBacklightTimeout(
                minutes = params.intOf('S'),
            )
        }
    }
}

/**
 * M256 [B<brightness>]
 *
 * LCD Brightness (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M256.html">MarlinFirmare M256 doc</a>
 */
data class LCDBrightness(
    /** `B` - brightness */
    val brightness: Int? = null,
) : GRQ<LCDBrightness> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (brightness != null) words.add(word('B', brightness))
        return M(256, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<LCDBrightness> {

        override fun head(): GParameterWord<*> {
            return M(256).head
        }

        override fun decodeParams(params: List<GWord>): LCDBrightness {
            return LCDBrightness(
                brightness = params.intOf('B'),
            )
        }
    }
}

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
) : GRQ<I2CSend> {

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

    companion object : GRQDecoder<I2CSend> {

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
) : GRQ<I2CRequest> {

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

    companion object : GRQDecoder<I2CRequest> {

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

/**
 * M265
 *
 * Scan I2C Bus (i2c).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M265.html">MarlinFirmare M265 doc</a>
 */
class ScanI2CBus : GRQ<ScanI2CBus> {

    override fun encode(): GCommand {
        return M(265)
    }

    override fun equals(other: Any?): Boolean {
        return other is ScanI2CBus
    }

    override fun hashCode(): Int {
        return 27275
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ScanI2CBus> {

        override fun head(): GParameterWord<*> {
            return M(265).head
        }

        override fun decodeParams(params: List<GWord>): ScanI2CBus {
            return ScanI2CBus()
        }
    }
}

/**
 * M280 P<index> S<pos>
 *
 * Servo Position (servos).
 *
 * Marlin documents `P`, `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M280.html">MarlinFirmare M280 doc</a>
 */
data class ServoPosition(
    /** `P` - index (required) */
    val index: Int? = null,
    /** `S` - pos (required) */
    val pos: Int? = null,
) : GRQ<ServoPosition> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (index != null) words.add(word('P', index))
        if (pos != null) words.add(word('S', pos))
        return M(280, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ServoPosition> {

        override fun head(): GParameterWord<*> {
            return M(280).head
        }

        override fun decodeParams(params: List<GWord>): ServoPosition {
            return ServoPosition(
                index = params.intOf('P'),
                pos = params.intOf('S'),
            )
        }
    }
}

/**
 * M281 P<index> [L<degrees>] [U<degrees>]
 *
 * Edit Servo Angles (servos).
 *
 * Marlin documents `P` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M281.html">MarlinFirmare M281 doc</a>
 */
data class EditServoAngles(
    /** `P` - index (required) */
    val index: Int? = null,
    /** `L` - degrees */
    val degrees: Int? = null,
    /** `U` - degrees */
    val u: Int? = null,
) : GRQ<EditServoAngles> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (index != null) words.add(word('P', index))
        if (degrees != null) words.add(word('L', degrees))
        if (u != null) words.add(word('U', u))
        return M(281, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<EditServoAngles> {

        override fun head(): GParameterWord<*> {
            return M(281).head
        }

        override fun decodeParams(params: List<GWord>): EditServoAngles {
            return EditServoAngles(
                index = params.intOf('P'),
                degrees = params.intOf('L'),
                u = params.intOf('U'),
            )
        }
    }
}

/**
 * M282 P<index>
 *
 * Detach Servo (servos).
 *
 * Marlin documents `P` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M282.html">MarlinFirmare M282 doc</a>
 */
data class DetachServo(
    /** `P` - index (required) */
    val index: Int? = null,
) : GRQ<DetachServo> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (index != null) words.add(word('P', index))
        return M(282, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<DetachServo> {

        override fun head(): GParameterWord<*> {
            return M(282).head
        }

        override fun decodeParams(params: List<GWord>): DetachServo {
            return DetachServo(
                index = params.intOf('P'),
            )
        }
    }
}

/**
 * M290 [X<pos>] [Y<pos>] [Z<pos>] [S<pos>] [P<value>]
 *
 * Babystep (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M290.html">MarlinFirmare M290 doc</a>
 */
data class Babystep(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `S` - pos */
    val s: BigDecimal? = null,
    /** `P` */
    val p: Boolean? = null,
) : GRQ<Babystep> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (p != null) words.add(word('P', if (p) 1 else 0))
        return M(290, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<Babystep> {

        override fun head(): GParameterWord<*> {
            return M(290).head
        }

        override fun decodeParams(params: List<GWord>): Babystep {
            return Babystep(
                pos = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                s = params.decimalOf('S'),
                p = params.boolOf('P'),
            )
        }
    }
}

/**
 * M300 [P<ms>] [S<value>]
 *
 * Play Tone (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M300.html">MarlinFirmare M300 doc</a>
 */
data class PlayTone(
    /** `P` - ms */
    val ms: Int? = null,
    /** `S` */
    val s: Int? = null,
) : GRQ<PlayTone> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (ms != null) words.add(word('P', ms))
        if (s != null) words.add(word('S', s))
        return M(300, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<PlayTone> {

        override fun head(): GParameterWord<*> {
            return M(300).head
        }

        override fun decodeParams(params: List<GWord>): PlayTone {
            return PlayTone(
                ms = params.intOf('P'),
                s = params.intOf('S'),
            )
        }
    }
}

/**
 * M301 [E<index>] [P<value>] [I<value>] [D<value>] [C<value>] [L<value>] [F<value>]
 *
 * Set Hotend PID (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M301.html">MarlinFirmare M301 doc</a>
 */
data class SetHotendPID(
    /** `E` - index */
    val index: Int? = null,
    /** `P` - value */
    val value: BigDecimal? = null,
    /** `I` - value */
    val i: BigDecimal? = null,
    /** `D` - value */
    val d: BigDecimal? = null,
    /** `C` - value */
    val c: BigDecimal? = null,
    /** `L` - value */
    val l: BigDecimal? = null,
    /** `F` - value */
    val f: BigDecimal? = null,
) : GRQ<SetHotendPID> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(7)
        if (index != null) words.add(word('E', index))
        if (value != null) words.add(word('P', value.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (d != null) words.add(word('D', d.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (f != null) words.add(word('F', f.toPlainString()))
        return M(301, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetHotendPID> {

        override fun head(): GParameterWord<*> {
            return M(301).head
        }

        override fun decodeParams(params: List<GWord>): SetHotendPID {
            return SetHotendPID(
                index = params.intOf('E'),
                value = params.decimalOf('P'),
                i = params.decimalOf('I'),
                d = params.decimalOf('D'),
                c = params.decimalOf('C'),
                l = params.decimalOf('L'),
                f = params.decimalOf('F'),
            )
        }
    }
}

/**
 * M302 [S<temp>] [P<value>]
 *
 * Cold Extrude (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M302.html">MarlinFirmare M302 doc</a>
 */
data class ColdExtrude(
    /** `S` - temp */
    val temp: BigDecimal? = null,
    /** `P` */
    val p: Boolean? = null,
) : GRQ<ColdExtrude> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (temp != null) words.add(word('S', temp.toPlainString()))
        if (p != null) words.add(word('P', if (p) 1 else 0))
        return M(302, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ColdExtrude> {

        override fun head(): GParameterWord<*> {
            return M(302).head
        }

        override fun decodeParams(params: List<GWord>): ColdExtrude {
            return ColdExtrude(
                temp = params.decimalOf('S'),
                p = params.boolOf('P'),
            )
        }
    }
}

/**
 * M303 [E<index>] [C<count>] [S<temp>] [U<value>] [D]
 *
 * PID autotune (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M303.html">MarlinFirmare M303 doc</a>
 */
data class PIDAutotune(
    /** `E` - index */
    val index: Int? = null,
    /** `C` - count */
    val count: Int? = null,
    /** `S` - temp */
    val temp: BigDecimal? = null,
    /** `U` */
    val u: Boolean? = null,
    /** `D` */
    val d: Boolean = false,
) : GRQ<PIDAutotune> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (index != null) words.add(word('E', index))
        if (count != null) words.add(word('C', count))
        if (temp != null) words.add(word('S', temp.toPlainString()))
        if (u != null) words.add(word('U', if (u) 1 else 0))
        if (d) words.add(flag('D'))
        return M(303, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<PIDAutotune> {

        override fun head(): GParameterWord<*> {
            return M(303).head
        }

        override fun decodeParams(params: List<GWord>): PIDAutotune {
            return PIDAutotune(
                index = params.intOf('E'),
                count = params.intOf('C'),
                temp = params.decimalOf('S'),
                u = params.boolOf('U'),
                d = params.hasWord('D'),
            )
        }
    }
}

/**
 * M304 [P<value>] [I<value>] [D<value>]
 *
 * Set Bed PID (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M304.html">MarlinFirmare M304 doc</a>
 */
data class SetBedPID(
    /** `P` - value */
    val value: BigDecimal? = null,
    /** `I` - value */
    val i: BigDecimal? = null,
    /** `D` - value */
    val d: BigDecimal? = null,
) : GRQ<SetBedPID> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (value != null) words.add(word('P', value.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (d != null) words.add(word('D', d.toPlainString()))
        return M(304, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetBedPID> {

        override fun head(): GParameterWord<*> {
            return M(304).head
        }

        override fun decodeParams(params: List<GWord>): SetBedPID {
            return SetBedPID(
                value = params.decimalOf('P'),
                i = params.decimalOf('I'),
                d = params.decimalOf('D'),
            )
        }
    }
}

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
) : GRQ<UserThermistorParameters> {

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

    companion object : GRQDecoder<UserThermistorParameters> {

        override fun head(): GParameterWord<*> {
            return M(305).head
        }

        override fun decodeParams(params: List<GWord>): UserThermistorParameters {
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

/**
 * M306 [A<value>] [C<value>] [E<index>] [F<value>] [H<value>] [P<value>] [R<value>] [S<value>] [T]
 *
 * Model Predictive Temp. Control (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M306.html">MarlinFirmare M306 doc</a>
 */
data class ModelPredictiveTempControl(
    /** `A` - value */
    val value: BigDecimal? = null,
    /** `C` - value */
    val c: BigDecimal? = null,
    /** `E` - index */
    val index: Int? = null,
    /** `F` - value */
    val f: BigDecimal? = null,
    /** `H` - value */
    val h: BigDecimal? = null,
    /** `P` - value */
    val p: BigDecimal? = null,
    /** `R` - value */
    val r: BigDecimal? = null,
    /** `S` */
    val s: Int? = null,
    /** `T` */
    val t: Boolean = false,
) : GRQ<ModelPredictiveTempControl> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(9)
        if (value != null) words.add(word('A', value.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (index != null) words.add(word('E', index))
        if (f != null) words.add(word('F', f.toPlainString()))
        if (h != null) words.add(word('H', h.toPlainString()))
        if (p != null) words.add(word('P', p.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (s != null) words.add(word('S', s))
        if (t) words.add(flag('T'))
        return M(306, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ModelPredictiveTempControl> {

        override fun head(): GParameterWord<*> {
            return M(306).head
        }

        override fun decodeParams(params: List<GWord>): ModelPredictiveTempControl {
            return ModelPredictiveTempControl(
                value = params.decimalOf('A'),
                c = params.decimalOf('C'),
                index = params.intOf('E'),
                f = params.decimalOf('F'),
                h = params.decimalOf('H'),
                p = params.decimalOf('P'),
                r = params.decimalOf('R'),
                s = params.intOf('S'),
                t = params.hasWord('T'),
            )
        }
    }
}

/**
 * M309 [P<value>] [I<value>] [D<value>]
 *
 * Set Chamber PID (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M309.html">MarlinFirmare M309 doc</a>
 */
data class SetChamberPID(
    /** `P` - value */
    val value: BigDecimal? = null,
    /** `I` - value */
    val i: BigDecimal? = null,
    /** `D` - value */
    val d: BigDecimal? = null,
) : GRQ<SetChamberPID> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (value != null) words.add(word('P', value.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (d != null) words.add(word('D', d.toPlainString()))
        return M(309, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetChamberPID> {

        override fun head(): GParameterWord<*> {
            return M(309).head
        }

        override fun decodeParams(params: List<GWord>): SetChamberPID {
            return SetChamberPID(
                value = params.decimalOf('P'),
                i = params.decimalOf('I'),
                d = params.decimalOf('D'),
            )
        }
    }
}

/**
 * M350 [B<value>] [S<value>] [X<value>] [Y<value>] [Z<value>] [A<value>] [C<value>] [U<value>] [V<value>] [W<value>] [E<value>]
 *
 * Set micro-stepping (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M350.html">MarlinFirmare M350 doc</a>
 */
data class SetMicroStepping(
    /** `B` */
    val b: Int? = null,
    /** `S` */
    val s: Int? = null,
    /** `X` */
    val x: Int? = null,
    /** `Y` */
    val y: Int? = null,
    /** `Z` */
    val z: Int? = null,
    /** `A` */
    val a: Int? = null,
    /** `C` */
    val c: Int? = null,
    /** `U` */
    val u: Int? = null,
    /** `V` */
    val v: Int? = null,
    /** `W` */
    val w: Int? = null,
    /** `E` */
    val e: Int? = null,
) : GRQ<SetMicroStepping> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(11)
        if (b != null) words.add(word('B', b))
        if (s != null) words.add(word('S', s))
        if (x != null) words.add(word('X', x))
        if (y != null) words.add(word('Y', y))
        if (z != null) words.add(word('Z', z))
        if (a != null) words.add(word('A', a))
        if (c != null) words.add(word('C', c))
        if (u != null) words.add(word('U', u))
        if (v != null) words.add(word('V', v))
        if (w != null) words.add(word('W', w))
        if (e != null) words.add(word('E', e))
        return M(350, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetMicroStepping> {

        override fun head(): GParameterWord<*> {
            return M(350).head
        }

        override fun decodeParams(params: List<GWord>): SetMicroStepping {
            return SetMicroStepping(
                b = params.intOf('B'),
                s = params.intOf('S'),
                x = params.intOf('X'),
                y = params.intOf('Y'),
                z = params.intOf('Z'),
                a = params.intOf('A'),
                c = params.intOf('C'),
                u = params.intOf('U'),
                v = params.intOf('V'),
                w = params.intOf('W'),
                e = params.intOf('E'),
            )
        }
    }
}

/**
 * M351 S<value> [B<value>] [X<value>] [Y<value>] [Z<value>] [E<value>]
 *
 * Set Microstep Pins (control).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M351.html">MarlinFirmare M351 doc</a>
 */
data class SetMicrostepPins(
    /** `S` (required) */
    val s: Int? = null,
    /** `B` */
    val b: Int? = null,
    /** `X` */
    val x: Int? = null,
    /** `Y` */
    val y: Int? = null,
    /** `Z` */
    val z: Int? = null,
    /** `E` */
    val e: Int? = null,
) : GRQ<SetMicrostepPins> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (s != null) words.add(word('S', s))
        if (b != null) words.add(word('B', b))
        if (x != null) words.add(word('X', x))
        if (y != null) words.add(word('Y', y))
        if (z != null) words.add(word('Z', z))
        if (e != null) words.add(word('E', e))
        return M(351, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetMicrostepPins> {

        override fun head(): GParameterWord<*> {
            return M(351).head
        }

        override fun decodeParams(params: List<GWord>): SetMicrostepPins {
            return SetMicrostepPins(
                s = params.intOf('S'),
                b = params.intOf('B'),
                x = params.intOf('X'),
                y = params.intOf('Y'),
                z = params.intOf('Z'),
                e = params.intOf('E'),
            )
        }
    }
}

/**
 * M355 [P<value>] [S<value>]
 *
 * Case Light Control (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M355.html">MarlinFirmare M355 doc</a>
 */
data class CaseLightControl(
    /** `P` */
    val p: Int? = null,
    /** `S` */
    val s: Boolean? = null,
) : GRQ<CaseLightControl> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (p != null) words.add(word('P', p))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return M(355, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<CaseLightControl> {

        override fun head(): GParameterWord<*> {
            return M(355).head
        }

        override fun decodeParams(params: List<GWord>): CaseLightControl {
            return CaseLightControl(
                p = params.intOf('P'),
                s = params.boolOf('S'),
            )
        }
    }
}

/**
 * M360
 *
 * SCARA Theta A (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M360.html">MarlinFirmare M360 doc</a>
 */
class SCARAThetaA : GRQ<SCARAThetaA> {

    override fun encode(): GCommand {
        return M(360)
    }

    override fun equals(other: Any?): Boolean {
        return other is SCARAThetaA
    }

    override fun hashCode(): Int {
        return 850344
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SCARAThetaA> {

        override fun head(): GParameterWord<*> {
            return M(360).head
        }

        override fun decodeParams(params: List<GWord>): SCARAThetaA {
            return SCARAThetaA()
        }
    }
}

/**
 * M361
 *
 * SCARA Theta-B (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M361.html">MarlinFirmare M361 doc</a>
 */
class SCARAThetaB : GRQ<SCARAThetaB> {

    override fun encode(): GCommand {
        return M(361)
    }

    override fun equals(other: Any?): Boolean {
        return other is SCARAThetaB
    }

    override fun hashCode(): Int {
        return 538258
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SCARAThetaB> {

        override fun head(): GParameterWord<*> {
            return M(361).head
        }

        override fun decodeParams(params: List<GWord>): SCARAThetaB {
            return SCARAThetaB()
        }
    }
}

/**
 * M362
 *
 * SCARA Psi-A (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M362.html">MarlinFirmare M362 doc</a>
 */
class SCARAPsiA : GRQ<SCARAPsiA> {

    override fun encode(): GCommand {
        return M(362)
    }

    override fun equals(other: Any?): Boolean {
        return other is SCARAPsiA
    }

    override fun hashCode(): Int {
        return 576920
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SCARAPsiA> {

        override fun head(): GParameterWord<*> {
            return M(362).head
        }

        override fun decodeParams(params: List<GWord>): SCARAPsiA {
            return SCARAPsiA()
        }
    }
}

/**
 * M363
 *
 * SCARA Psi-B (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M363.html">MarlinFirmare M363 doc</a>
 */
class SCARAPsiB : GRQ<SCARAPsiB> {

    override fun encode(): GCommand {
        return M(363)
    }

    override fun equals(other: Any?): Boolean {
        return other is SCARAPsiB
    }

    override fun hashCode(): Int {
        return 962082
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SCARAPsiB> {

        override fun head(): GParameterWord<*> {
            return M(363).head
        }

        override fun decodeParams(params: List<GWord>): SCARAPsiB {
            return SCARAPsiB()
        }
    }
}

/**
 * M364
 *
 * SCARA Psi-C (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M364.html">MarlinFirmare M364 doc</a>
 */
class SCARAPsiC : GRQ<SCARAPsiC> {

    override fun encode(): GCommand {
        return M(364)
    }

    override fun equals(other: Any?): Boolean {
        return other is SCARAPsiC
    }

    override fun hashCode(): Int {
        return 774900
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SCARAPsiC> {

        override fun head(): GParameterWord<*> {
            return M(364).head
        }

        override fun decodeParams(params: List<GWord>): SCARAPsiC {
            return SCARAPsiC()
        }
    }
}

/**
 * M380 [S<index>]
 *
 * Activate Solenoid.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M380.html">MarlinFirmare M380 doc</a>
 */
data class ActivateSolenoid(
    /** `S` - index */
    val index: Int? = null,
) : GRQ<ActivateSolenoid> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (index != null) words.add(word('S', index))
        return M(380, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ActivateSolenoid> {

        override fun head(): GParameterWord<*> {
            return M(380).head
        }

        override fun decodeParams(params: List<GWord>): ActivateSolenoid {
            return ActivateSolenoid(
                index = params.intOf('S'),
            )
        }
    }
}

/**
 * M381 [S<index>]
 *
 * Deactivate Solenoids.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M381.html">MarlinFirmare M381 doc</a>
 */
data class DeactivateSolenoids(
    /** `S` - index */
    val index: Int? = null,
) : GRQ<DeactivateSolenoids> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (index != null) words.add(word('S', index))
        return M(381, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<DeactivateSolenoids> {

        override fun head(): GParameterWord<*> {
            return M(381).head
        }

        override fun decodeParams(params: List<GWord>): DeactivateSolenoids {
            return DeactivateSolenoids(
                index = params.intOf('S'),
            )
        }
    }
}

/**
 * M400
 *
 * Finish Moves (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M400.html">MarlinFirmare M400 doc</a>
 */
class FinishMoves : GRQ<FinishMoves> {

    override fun encode(): GCommand {
        return M(400)
    }

    override fun equals(other: Any?): Boolean {
        return other is FinishMoves
    }

    override fun hashCode(): Int {
        return 143606
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FinishMoves> {

        override fun head(): GParameterWord<*> {
            return M(400).head
        }

        override fun decodeParams(params: List<GWord>): FinishMoves {
            return FinishMoves()
        }
    }
}

/**
 * M401 [H] [S<value>] [R<value>]
 *
 * Deploy Probe (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M401.html">MarlinFirmare M401 doc</a>
 */
data class DeployProbe(
    /** `H` */
    val h: Boolean = false,
    /** `S` */
    val s: Boolean? = null,
    /** `R` */
    val r: Boolean? = null,
) : GRQ<DeployProbe> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (h) words.add(flag('H'))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        if (r != null) words.add(word('R', if (r) 1 else 0))
        return M(401, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<DeployProbe> {

        override fun head(): GParameterWord<*> {
            return M(401).head
        }

        override fun decodeParams(params: List<GWord>): DeployProbe {
            return DeployProbe(
                h = params.hasWord('H'),
                s = params.boolOf('S'),
                r = params.boolOf('R'),
            )
        }
    }
}

/**
 * M402 [R<value>]
 *
 * Stow Probe (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M402.html">MarlinFirmare M402 doc</a>
 */
data class StowProbe(
    /** `R` */
    val r: Boolean? = null,
) : GRQ<StowProbe> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (r != null) words.add(word('R', if (r) 1 else 0))
        return M(402, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<StowProbe> {

        override fun head(): GParameterWord<*> {
            return M(402).head
        }

        override fun decodeParams(params: List<GWord>): StowProbe {
            return StowProbe(
                r = params.boolOf('R'),
            )
        }
    }
}

/**
 * M403 E<index> F<value>
 *
 * MMU2 Filament Type (control).
 *
 * Marlin documents `E`, `F` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M403.html">MarlinFirmare M403 doc</a>
 */
data class MMU2FilamentType(
    /** `E` - index (required) */
    val index: Int? = null,
    /** `F` (required) */
    val f: Int? = null,
) : GRQ<MMU2FilamentType> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (index != null) words.add(word('E', index))
        if (f != null) words.add(word('F', f))
        return M(403, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<MMU2FilamentType> {

        override fun head(): GParameterWord<*> {
            return M(403).head
        }

        override fun decodeParams(params: List<GWord>): MMU2FilamentType {
            return MMU2FilamentType(
                index = params.intOf('E'),
                f = params.intOf('F'),
            )
        }
    }
}

/**
 * M404 [W<linear>]
 *
 * Filament Width Sensor Nominal Diameter (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M404.html">MarlinFirmare M404 doc</a>
 */
data class FilamentWidthSensorNominalDiameter(
    /** `W` - linear */
    val linear: BigDecimal? = null,
) : GRQ<FilamentWidthSensorNominalDiameter> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (linear != null) words.add(word('W', linear.toPlainString()))
        return M(404, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FilamentWidthSensorNominalDiameter> {

        override fun head(): GParameterWord<*> {
            return M(404).head
        }

        override fun decodeParams(params: List<GWord>): FilamentWidthSensorNominalDiameter {
            return FilamentWidthSensorNominalDiameter(
                linear = params.decimalOf('W'),
            )
        }
    }
}

/**
 * M405 [D<value>]
 *
 * Filament Width Sensor On (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M405.html">MarlinFirmare M405 doc</a>
 */
data class FilamentWidthSensorOn(
    /** `D` */
    val d: Int? = null,
) : GRQ<FilamentWidthSensorOn> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (d != null) words.add(word('D', d))
        return M(405, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FilamentWidthSensorOn> {

        override fun head(): GParameterWord<*> {
            return M(405).head
        }

        override fun decodeParams(params: List<GWord>): FilamentWidthSensorOn {
            return FilamentWidthSensorOn(
                d = params.intOf('D'),
            )
        }
    }
}

/**
 * M406
 *
 * Filament Width Sensor Off (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M406.html">MarlinFirmare M406 doc</a>
 */
class FilamentWidthSensorOff : GRQ<FilamentWidthSensorOff> {

    override fun encode(): GCommand {
        return M(406)
    }

    override fun equals(other: Any?): Boolean {
        return other is FilamentWidthSensorOff
    }

    override fun hashCode(): Int {
        return 574854
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FilamentWidthSensorOff> {

        override fun head(): GParameterWord<*> {
            return M(406).head
        }

        override fun decodeParams(params: List<GWord>): FilamentWidthSensorOff {
            return FilamentWidthSensorOff()
        }
    }
}

/**
 * M407
 *
 * Read Filament Width (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M407.html">MarlinFirmare M407 doc</a>
 */
class ReadFilamentWidth : GRQ<ReadFilamentWidth> {

    override fun encode(): GCommand {
        return M(407)
    }

    override fun equals(other: Any?): Boolean {
        return other is ReadFilamentWidth
    }

    override fun hashCode(): Int {
        return 67696
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ReadFilamentWidth> {

        override fun head(): GParameterWord<*> {
            return M(407).head
        }

        override fun decodeParams(params: List<GWord>): ReadFilamentWidth {
            return ReadFilamentWidth()
        }
    }
}

/**
 * M410
 *
 * Quickstop (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M410.html">MarlinFirmare M410 doc</a>
 */
class Quickstop : GRQ<Quickstop> {

    override fun encode(): GCommand {
        return M(410)
    }

    override fun equals(other: Any?): Boolean {
        return other is Quickstop
    }

    override fun hashCode(): Int {
        return 285083
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<Quickstop> {

        override fun head(): GParameterWord<*> {
            return M(410).head
        }

        override fun decodeParams(params: List<GWord>): Quickstop {
            return Quickstop()
        }
    }
}

/**
 * M412 [D<linear>] [H<value>] [L<linear>] [S<value>] [R<value>]
 *
 * Filament Runout (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M412.html">MarlinFirmare M412 doc</a>
 */
data class FilamentRunout(
    /** `D` - linear */
    val linear: BigDecimal? = null,
    /** `H` */
    val h: Boolean? = null,
    /** `L` - linear */
    val l: BigDecimal? = null,
    /** `S` */
    val s: Boolean? = null,
    /** `R` */
    val r: Boolean? = null,
) : GRQ<FilamentRunout> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (linear != null) words.add(word('D', linear.toPlainString()))
        if (h != null) words.add(word('H', if (h) 1 else 0))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        if (r != null) words.add(word('R', if (r) 1 else 0))
        return M(412, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FilamentRunout> {

        override fun head(): GParameterWord<*> {
            return M(412).head
        }

        override fun decodeParams(params: List<GWord>): FilamentRunout {
            return FilamentRunout(
                linear = params.decimalOf('D'),
                h = params.boolOf('H'),
                l = params.decimalOf('L'),
                s = params.boolOf('S'),
                r = params.boolOf('R'),
            )
        }
    }
}

/**
 * M413 [S<value>]
 *
 * Power-loss Recovery (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M413.html">MarlinFirmare M413 doc</a>
 */
data class PowerLossRecovery(
    /** `S` */
    val s: Boolean? = null,
) : GRQ<PowerLossRecovery> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return M(413, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<PowerLossRecovery> {

        override fun head(): GParameterWord<*> {
            return M(413).head
        }

        override fun decodeParams(params: List<GWord>): PowerLossRecovery {
            return PowerLossRecovery(
                s = params.boolOf('S'),
            )
        }
    }
}

/**
 * M414 [S<language-index>]
 *
 * LCD language (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M414.html">MarlinFirmare M414 doc</a>
 */
data class LCDLanguage(
    /** `S` - language-index */
    val languageIndex: Int? = null,
) : GRQ<LCDLanguage> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (languageIndex != null) words.add(word('S', languageIndex))
        return M(414, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<LCDLanguage> {

        override fun head(): GParameterWord<*> {
            return M(414).head
        }

        override fun decodeParams(params: List<GWord>): LCDLanguage {
            return LCDLanguage(
                languageIndex = params.intOf('S'),
            )
        }
    }
}

/**
 * M420 [L<value>] [S<value>] [V<value>] [T<value>] [Z<linear>] [C<negative_offset>]
 *
 * Bed Leveling State (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M420.html">MarlinFirmare M420 doc</a>
 */
data class BedLevelingState(
    /** `L` */
    val l: Int? = null,
    /** `S` */
    val s: Boolean? = null,
    /** `V` */
    val v: Boolean? = null,
    /** `T` */
    val t: Int? = null,
    /** `Z` - linear */
    val linear: BigDecimal? = null,
    /** `C` - negative_offset */
    val negativeOffset: BigDecimal? = null,
) : GRQ<BedLevelingState> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (l != null) words.add(word('L', l))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        if (v != null) words.add(word('V', if (v) 1 else 0))
        if (t != null) words.add(word('T', t))
        if (linear != null) words.add(word('Z', linear.toPlainString()))
        if (negativeOffset != null) words.add(word('C', negativeOffset.toPlainString()))
        return M(420, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<BedLevelingState> {

        override fun head(): GParameterWord<*> {
            return M(420).head
        }

        override fun decodeParams(params: List<GWord>): BedLevelingState {
            return BedLevelingState(
                l = params.intOf('L'),
                s = params.boolOf('S'),
                v = params.boolOf('V'),
                t = params.intOf('T'),
                linear = params.decimalOf('Z'),
                negativeOffset = params.decimalOf('C'),
            )
        }
    }
}

/**
 * M421 [I<index>] [J<index>] [X<linear>] [Y<linear>] [Z<linear>] [Q<linear>] [C<value>] [N<value>]
 *
 * Set Mesh Value (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M421.html">MarlinFirmare M421 doc</a>
 */
data class SetMeshValue(
    /** `I` - index */
    val index: Int? = null,
    /** `J` - index */
    val j: Int? = null,
    /** `X` - linear */
    val linear: BigDecimal? = null,
    /** `Y` - linear */
    val y: BigDecimal? = null,
    /** `Z` - linear */
    val z: BigDecimal? = null,
    /** `Q` - linear */
    val q: BigDecimal? = null,
    /** `C` */
    val c: Boolean? = null,
    /** `N` */
    val n: Boolean? = null,
) : GRQ<SetMeshValue> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (index != null) words.add(word('I', index))
        if (j != null) words.add(word('J', j))
        if (linear != null) words.add(word('X', linear.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (q != null) words.add(word('Q', q.toPlainString()))
        if (c != null) words.add(word('C', if (c) 1 else 0))
        if (n != null) words.add(word('N', if (n) 1 else 0))
        return M(421, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetMeshValue> {

        override fun head(): GParameterWord<*> {
            return M(421).head
        }

        override fun decodeParams(params: List<GWord>): SetMeshValue {
            return SetMeshValue(
                index = params.intOf('I'),
                j = params.intOf('J'),
                linear = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                q = params.decimalOf('Q'),
                c = params.boolOf('C'),
                n = params.boolOf('N'),
            )
        }
    }
}

/**
 * M422 [R] [S<index>] [W<index>] [X<linear>] [Y<linear>]
 *
 * Set Z Motor XY (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M422.html">MarlinFirmare M422 doc</a>
 */
data class SetZMotorXY(
    /** `R` */
    val r: Boolean = false,
    /** `S` - index */
    val index: Int? = null,
    /** `W` - index */
    val w: Int? = null,
    /** `X` - linear */
    val linear: BigDecimal? = null,
    /** `Y` - linear */
    val y: BigDecimal? = null,
) : GRQ<SetZMotorXY> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (r) words.add(flag('R'))
        if (index != null) words.add(word('S', index))
        if (w != null) words.add(word('W', w))
        if (linear != null) words.add(word('X', linear.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        return M(422, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetZMotorXY> {

        override fun head(): GParameterWord<*> {
            return M(422).head
        }

        override fun decodeParams(params: List<GWord>): SetZMotorXY {
            return SetZMotorXY(
                r = params.hasWord('R'),
                index = params.intOf('S'),
                w = params.intOf('W'),
                linear = params.decimalOf('X'),
                y = params.decimalOf('Y'),
            )
        }
    }
}

/**
 * M423 [R] [A<linear>] [E<linear>] [I<linear>] [X<index>] [Z<linear>]
 *
 * X Twist Compensation (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M423.html">MarlinFirmare M423 doc</a>
 */
data class XTwistCompensation(
    /** `R` */
    val r: Boolean = false,
    /** `A` - linear */
    val linear: BigDecimal? = null,
    /** `E` - linear */
    val e: BigDecimal? = null,
    /** `I` - linear */
    val i: BigDecimal? = null,
    /** `X` - index */
    val index: Int? = null,
    /** `Z` - linear */
    val z: BigDecimal? = null,
) : GRQ<XTwistCompensation> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (r) words.add(flag('R'))
        if (linear != null) words.add(word('A', linear.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (index != null) words.add(word('X', index))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(423, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<XTwistCompensation> {

        override fun head(): GParameterWord<*> {
            return M(423).head
        }

        override fun decodeParams(params: List<GWord>): XTwistCompensation {
            return XTwistCompensation(
                r = params.hasWord('R'),
                linear = params.decimalOf('A'),
                e = params.decimalOf('E'),
                i = params.decimalOf('I'),
                index = params.intOf('X'),
                z = params.decimalOf('Z'),
            )
        }
    }
}

/**
 * M425 [F<value>] [S<linear>] [X<linear>] [Y<linear>] [Z<linear>]
 *
 * Backlash compensation (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M425.html">MarlinFirmare M425 doc</a>
 */
data class BacklashCompensation(
    /** `F` - value */
    val value: BigDecimal? = null,
    /** `S` - linear */
    val linear: BigDecimal? = null,
    /** `X` - linear */
    val x: BigDecimal? = null,
    /** `Y` - linear */
    val y: BigDecimal? = null,
    /** `Z` - linear */
    val z: BigDecimal? = null,
) : GRQ<BacklashCompensation> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (value != null) words.add(word('F', value.toPlainString()))
        if (linear != null) words.add(word('S', linear.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(425, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<BacklashCompensation> {

        override fun head(): GParameterWord<*> {
            return M(425).head
        }

        override fun decodeParams(params: List<GWord>): BacklashCompensation {
            return BacklashCompensation(
                value = params.decimalOf('F'),
                linear = params.decimalOf('S'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
            )
        }
    }
}

/**
 * M428
 *
 * Home Offsets Here (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M428.html">MarlinFirmare M428 doc</a>
 */
class HomeOffsetsHere : GRQ<HomeOffsetsHere> {

    override fun encode(): GCommand {
        return M(428)
    }

    override fun equals(other: Any?): Boolean {
        return other is HomeOffsetsHere
    }

    override fun hashCode(): Int {
        return 668937
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<HomeOffsetsHere> {

        override fun head(): GParameterWord<*> {
            return M(428).head
        }

        override fun decodeParams(params: List<GWord>): HomeOffsetsHere {
            return HomeOffsetsHere()
        }
    }
}

/**
 * M430 [I<value>] [V<value>] [W<value>]
 *
 * Power Monitor (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M430.html">MarlinFirmare M430 doc</a>
 */
data class PowerMonitor(
    /** `I` */
    val i: Boolean? = null,
    /** `V` */
    val v: Boolean? = null,
    /** `W` */
    val w: Boolean? = null,
) : GRQ<PowerMonitor> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (i != null) words.add(word('I', if (i) 1 else 0))
        if (v != null) words.add(word('V', if (v) 1 else 0))
        if (w != null) words.add(word('W', if (w) 1 else 0))
        return M(430, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<PowerMonitor> {

        override fun head(): GParameterWord<*> {
            return M(430).head
        }

        override fun decodeParams(params: List<GWord>): PowerMonitor {
            return PowerMonitor(
                i = params.boolOf('I'),
                v = params.boolOf('V'),
                w = params.boolOf('W'),
            )
        }
    }
}

/**
 * M486 [C] [P<index>] [S<index>] [T<count>] [U<index>]
 *
 * Cancel Objects (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M486.html">MarlinFirmare M486 doc</a>
 */
data class CancelObjects(
    /** `C` */
    val c: Boolean = false,
    /** `P` - index */
    val index: Int? = null,
    /** `S` - index */
    val s: Int? = null,
    /** `T` - count */
    val count: Int? = null,
    /** `U` - index */
    val u: Int? = null,
) : GRQ<CancelObjects> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (c) words.add(flag('C'))
        if (index != null) words.add(word('P', index))
        if (s != null) words.add(word('S', s))
        if (count != null) words.add(word('T', count))
        if (u != null) words.add(word('U', u))
        return M(486, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<CancelObjects> {

        override fun head(): GParameterWord<*> {
            return M(486).head
        }

        override fun decodeParams(params: List<GWord>): CancelObjects {
            return CancelObjects(
                c = params.hasWord('C'),
                index = params.intOf('P'),
                s = params.intOf('S'),
                count = params.intOf('T'),
                u = params.intOf('U'),
            )
        }
    }
}

/**
 * M493 [S<value>] [H<value>] [C<value>] [D<value>] [A<value>] [F<scale>] [I<zeta>] [Q<vtol>] [X] [Y] [Z] [E]
 *
 * Fixed-Time Motion (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M493.html">MarlinFirmare M493 doc</a>
 */
data class FixedTimeMotion(
    /** `S` */
    val s: Int? = null,
    /** `H` */
    val h: Int? = null,
    /** `C` */
    val c: Int? = null,
    /** `D` */
    val d: Int? = null,
    /** `A` */
    val a: BigDecimal? = null,
    /** `F` - scale */
    val scale: BigDecimal? = null,
    /** `I` - zeta */
    val zeta: BigDecimal? = null,
    /** `Q` - vtol */
    val vtol: BigDecimal? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: Boolean = false,
) : GRQ<FixedTimeMotion> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(12)
        if (s != null) words.add(word('S', s))
        if (h != null) words.add(word('H', h))
        if (c != null) words.add(word('C', c))
        if (d != null) words.add(word('D', d))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (scale != null) words.add(word('F', scale.toPlainString()))
        if (zeta != null) words.add(word('I', zeta.toPlainString()))
        if (vtol != null) words.add(word('Q', vtol.toPlainString()))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e) words.add(flag('E'))
        return M(493, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FixedTimeMotion> {

        override fun head(): GParameterWord<*> {
            return M(493).head
        }

        override fun decodeParams(params: List<GWord>): FixedTimeMotion {
            return FixedTimeMotion(
                s = params.intOf('S'),
                h = params.intOf('H'),
                c = params.intOf('C'),
                d = params.intOf('D'),
                a = params.decimalOf('A'),
                scale = params.decimalOf('F'),
                zeta = params.decimalOf('I'),
                vtol = params.decimalOf('Q'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
            )
        }
    }
}

/**
 * M494 [T] [O] [X] [Y] [Z] [E]
 *
 * FT Motion Trajectory Smoothing (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M494.html">MarlinFirmare M494 doc</a>
 */
data class FTMotionTrajectorySmoothing(
    /** `T` */
    val t: Boolean = false,
    /** `O` */
    val o: Boolean = false,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: Boolean = false,
) : GRQ<FTMotionTrajectorySmoothing> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (t) words.add(flag('T'))
        if (o) words.add(flag('O'))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e) words.add(flag('E'))
        return M(494, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FTMotionTrajectorySmoothing> {

        override fun head(): GParameterWord<*> {
            return M(494).head
        }

        override fun decodeParams(params: List<GWord>): FTMotionTrajectorySmoothing {
            return FTMotionTrajectorySmoothing(
                t = params.hasWord('T'),
                o = params.hasWord('O'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
            )
        }
    }
}

/**
 * M500
 *
 * Save Settings (eeprom).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M500.html">MarlinFirmare M500 doc</a>
 */
class SaveSettings : GRQ<SaveSettings> {

    override fun encode(): GCommand {
        return M(500)
    }

    override fun equals(other: Any?): Boolean {
        return other is SaveSettings
    }

    override fun hashCode(): Int {
        return 359397
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SaveSettings> {

        override fun head(): GParameterWord<*> {
            return M(500).head
        }

        override fun decodeParams(params: List<GWord>): SaveSettings {
            return SaveSettings()
        }
    }
}

/**
 * M501
 *
 * Restore Settings (eeprom).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M501.html">MarlinFirmare M501 doc</a>
 */
class RestoreSettings : GRQ<RestoreSettings> {

    override fun encode(): GCommand {
        return M(501)
    }

    override fun equals(other: Any?): Boolean {
        return other is RestoreSettings
    }

    override fun hashCode(): Int {
        return 848523
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<RestoreSettings> {

        override fun head(): GParameterWord<*> {
            return M(501).head
        }

        override fun decodeParams(params: List<GWord>): RestoreSettings {
            return RestoreSettings()
        }
    }
}

/**
 * M502
 *
 * Factory Reset (eeprom).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M502.html">MarlinFirmare M502 doc</a>
 */
class FactoryReset : GRQ<FactoryReset> {

    override fun encode(): GCommand {
        return M(502)
    }

    override fun equals(other: Any?): Boolean {
        return other is FactoryReset
    }

    override fun hashCode(): Int {
        return 130213
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FactoryReset> {

        override fun head(): GParameterWord<*> {
            return M(502).head
        }

        override fun decodeParams(params: List<GWord>): FactoryReset {
            return FactoryReset()
        }
    }
}

/**
 * M503 [S] [C]
 *
 * Report Settings (eeprom).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M503.html">MarlinFirmare M503 doc</a>
 */
data class ReportSettings(
    /** `S` */
    val s: Boolean = false,
    /** `C` */
    val c: Boolean = false,
) : GRQ<ReportSettings> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (s) words.add(flag('S'))
        if (c) words.add(flag('C'))
        return M(503, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ReportSettings> {

        override fun head(): GParameterWord<*> {
            return M(503).head
        }

        override fun decodeParams(params: List<GWord>): ReportSettings {
            return ReportSettings(
                s = params.hasWord('S'),
                c = params.hasWord('C'),
            )
        }
    }
}

/**
 * M504
 *
 * Validate EEPROM contents (eeprom).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M504.html">MarlinFirmare M504 doc</a>
 */
class ValidateEEPROMContents : GRQ<ValidateEEPROMContents> {

    override fun encode(): GCommand {
        return M(504)
    }

    override fun equals(other: Any?): Boolean {
        return other is ValidateEEPROMContents
    }

    override fun hashCode(): Int {
        return 223955
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ValidateEEPROMContents> {

        override fun head(): GParameterWord<*> {
            return M(504).head
        }

        override fun decodeParams(params: List<GWord>): ValidateEEPROMContents {
            return ValidateEEPROMContents()
        }
    }
}

/**
 * M510
 *
 * Lock Machine (security).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M510.html">MarlinFirmare M510 doc</a>
 */
class LockMachine : GRQ<LockMachine> {

    override fun encode(): GCommand {
        return M(510)
    }

    override fun equals(other: Any?): Boolean {
        return other is LockMachine
    }

    override fun hashCode(): Int {
        return 67795
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<LockMachine> {

        override fun head(): GParameterWord<*> {
            return M(510).head
        }

        override fun decodeParams(params: List<GWord>): LockMachine {
            return LockMachine()
        }
    }
}

/**
 * M511 P<passcode>
 *
 * Unlock Machine (security).
 *
 * Marlin documents `P` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M511.html">MarlinFirmare M511 doc</a>
 */
data class UnlockMachine(
    /** `P` - passcode (required) */
    val passcode: Int? = null,
) : GRQ<UnlockMachine> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (passcode != null) words.add(word('P', passcode))
        return M(511, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<UnlockMachine> {

        override fun head(): GParameterWord<*> {
            return M(511).head
        }

        override fun decodeParams(params: List<GWord>): UnlockMachine {
            return UnlockMachine(
                passcode = params.intOf('P'),
            )
        }
    }
}

/**
 * M512 [P<password>] [S<password>]
 *
 * Set Passcode (security).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M512.html">MarlinFirmare M512 doc</a>
 */
data class SetPasscode(
    /** `P` - password */
    val password: Int? = null,
    /** `S` - password */
    val s: Int? = null,
) : GRQ<SetPasscode> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (password != null) words.add(word('P', password))
        if (s != null) words.add(word('S', s))
        return M(512, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetPasscode> {

        override fun head(): GParameterWord<*> {
            return M(512).head
        }

        override fun decodeParams(params: List<GWord>): SetPasscode {
            return SetPasscode(
                password = params.intOf('P'),
                s = params.intOf('S'),
            )
        }
    }
}

/**
 * M524
 *
 * Abort SD print (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M524.html">MarlinFirmare M524 doc</a>
 */
class AbortSDPrint : GRQ<AbortSDPrint> {

    override fun encode(): GCommand {
        return M(524)
    }

    override fun equals(other: Any?): Boolean {
        return other is AbortSDPrint
    }

    override fun hashCode(): Int {
        return 873954
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<AbortSDPrint> {

        override fun head(): GParameterWord<*> {
            return M(524).head
        }

        override fun decodeParams(params: List<GWord>): AbortSDPrint {
            return AbortSDPrint()
        }
    }
}

/**
 * M540 S<flag>
 *
 * Endstops Abort SD (sdcard).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M540.html">MarlinFirmare M540 doc</a>
 */
data class EndstopsAbortSD(
    /** `S` - flag (required) */
    val flag: Boolean? = null,
) : GRQ<EndstopsAbortSD> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (flag != null) words.add(word('S', if (flag) 1 else 0))
        return M(540, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<EndstopsAbortSD> {

        override fun head(): GParameterWord<*> {
            return M(540).head
        }

        override fun decodeParams(params: List<GWord>): EndstopsAbortSD {
            return EndstopsAbortSD(
                flag = params.boolOf('S'),
            )
        }
    }
}

/**
 * M550 [P<name>]
 *
 * Machine Name (hosts).
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M550.html">MarlinFirmare M550 doc</a>
 */
data class MachineName(
    /** `P` - name */
    val name: String? = null,
) : GRQ<MachineName> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (name != null) words.add(word('P', text(name)))
        return M(550, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<MachineName> {

        override fun head(): GParameterWord<*> {
            return M(550).head
        }

        override fun decodeParams(params: List<GWord>): MachineName {
            return MachineName(
                name = params.stringOf('P'),
            )
        }
    }
}

/**
 * M552 [P<ip-address>] [S<value>]
 *
 * Ethernet IP Address, Network IF (ethernet).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M552.html">MarlinFirmare M552 doc</a>
 */
data class EthernetIPAddressNetworkIF(
    /** `P` - ip-address */
    val ipAddress: String? = null,
    /** `S` */
    val s: Int? = null,
) : GRQ<EthernetIPAddressNetworkIF> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (ipAddress != null) words.add(word('P', text(ipAddress)))
        if (s != null) words.add(word('S', s))
        return M(552, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<EthernetIPAddressNetworkIF> {

        override fun head(): GParameterWord<*> {
            return M(552).head
        }

        override fun decodeParams(params: List<GWord>): EthernetIPAddressNetworkIF {
            return EthernetIPAddressNetworkIF(
                ipAddress = params.stringOf('P'),
                s = params.intOf('S'),
            )
        }
    }
}

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
) : GRQ<EthernetSubnetMask> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (subnetMask != null) words.add(word('P', text(subnetMask)))
        return M(553, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<EthernetSubnetMask> {

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

/**
 * M554 [P<gateway>]
 *
 * Ethernet Gateway IP Address (ethernet).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M554.html">MarlinFirmare M554 doc</a>
 */
data class EthernetGatewayIPAddress(
    /** `P` - gateway */
    val gateway: String? = null,
) : GRQ<EthernetGatewayIPAddress> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (gateway != null) words.add(word('P', text(gateway)))
        return M(554, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<EthernetGatewayIPAddress> {

        override fun head(): GParameterWord<*> {
            return M(554).head
        }

        override fun decodeParams(params: List<GWord>): EthernetGatewayIPAddress {
            return EthernetGatewayIPAddress(
                gateway = params.stringOf('P'),
            )
        }
    }
}

/**
 * M569 [X] [Y] [Z] [E] [I<value>] [T<value>]
 *
 * Set TMC Stepping Mode (trinamic).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M569.html">MarlinFirmare M569 doc</a>
 */
data class SetTMCSteppingMode(
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: Boolean = false,
    /** `I` */
    val i: Int? = null,
    /** `T` */
    val t: Int? = null,
) : GRQ<SetTMCSteppingMode> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e) words.add(flag('E'))
        if (i != null) words.add(word('I', i))
        if (t != null) words.add(word('T', t))
        return M(569, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetTMCSteppingMode> {

        override fun head(): GParameterWord<*> {
            return M(569).head
        }

        override fun decodeParams(params: List<GWord>): SetTMCSteppingMode {
            return SetTMCSteppingMode(
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                i = params.intOf('I'),
                t = params.intOf('T'),
            )
        }
    }
}

/**
 * M575 [P] B<baud>
 *
 * Serial baud rate (hosts).
 *
 * Marlin documents `B` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M575.html">MarlinFirmare M575 doc</a>
 */
data class SerialBaudRate(
    /** `P` */
    val p: Boolean = false,
    /** `B` - baud (required) */
    val baud: BigDecimal? = null,
) : GRQ<SerialBaudRate> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (p) words.add(flag('P'))
        if (baud != null) words.add(word('B', baud.toPlainString()))
        return M(575, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SerialBaudRate> {

        override fun head(): GParameterWord<*> {
            return M(575).head
        }

        override fun decodeParams(params: List<GWord>): SerialBaudRate {
            return SerialBaudRate(
                p = params.hasWord('P'),
                baud = params.decimalOf('B'),
            )
        }
    }
}

/**
 * M592 [A<coeff>] [B<coeff>] [C<coeff>] [S]
 *
 * Nonlinear Extrusion Control (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M592.html">MarlinFirmare M592 doc</a>
 */
data class NonlinearExtrusionControl(
    /** `A` - coeff */
    val coeff: BigDecimal? = null,
    /** `B` - coeff */
    val b: BigDecimal? = null,
    /** `C` - coeff */
    val c: BigDecimal? = null,
    /** `S` */
    val s: Boolean = false,
) : GRQ<NonlinearExtrusionControl> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (coeff != null) words.add(word('A', coeff.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (s) words.add(flag('S'))
        return M(592, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<NonlinearExtrusionControl> {

        override fun head(): GParameterWord<*> {
            return M(592).head
        }

        override fun decodeParams(params: List<GWord>): NonlinearExtrusionControl {
            return NonlinearExtrusionControl(
                coeff = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                s = params.hasWord('S'),
            )
        }
    }
}

/**
 * M593 [D<zeta>] [F<hertz>] [X] [Y] [Z]
 *
 * ZV Input Shaping (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M593.html">MarlinFirmare M593 doc</a>
 */
data class ZVInputShaping(
    /** `D` - zeta */
    val zeta: BigDecimal? = null,
    /** `F` - hertz */
    val hertz: BigDecimal? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
) : GRQ<ZVInputShaping> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (zeta != null) words.add(word('D', zeta.toPlainString()))
        if (hertz != null) words.add(word('F', hertz.toPlainString()))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        return M(593, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ZVInputShaping> {

        override fun head(): GParameterWord<*> {
            return M(593).head
        }

        override fun decodeParams(params: List<GWord>): ZVInputShaping {
            return ZVInputShaping(
                zeta = params.decimalOf('D'),
                hertz = params.decimalOf('F'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
            )
        }
    }
}

/**
 * M600 [T<index>] [E<pos>] [U<pos>] [L<pos>] [X<pos>] [Y<pos>] [Z<pos>] [B<beeps>] [R<temp>]
 *
 * Filament Change (filament).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M600.html">MarlinFirmare M600 doc</a>
 */
data class FilamentChange(
    /** `T` - index */
    val index: Int? = null,
    /** `E` - pos */
    val pos: BigDecimal? = null,
    /** `U` - pos */
    val u: BigDecimal? = null,
    /** `L` - pos */
    val l: BigDecimal? = null,
    /** `X` - pos */
    val x: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `B` - beeps */
    val beeps: Int? = null,
    /** `R` - temp */
    val temp: Int? = null,
) : GRQ<FilamentChange> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(9)
        if (index != null) words.add(word('T', index))
        if (pos != null) words.add(word('E', pos.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (beeps != null) words.add(word('B', beeps))
        if (temp != null) words.add(word('R', temp))
        return M(600, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FilamentChange> {

        override fun head(): GParameterWord<*> {
            return M(600).head
        }

        override fun decodeParams(params: List<GWord>): FilamentChange {
            return FilamentChange(
                index = params.intOf('T'),
                pos = params.decimalOf('E'),
                u = params.decimalOf('U'),
                l = params.decimalOf('L'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                beeps = params.intOf('B'),
                temp = params.intOf('R'),
            )
        }
    }
}

/**
 * M603 [T<index>] [U<pos>] [L<pos>]
 *
 * Configure Filament Change (filament).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M603.html">MarlinFirmare M603 doc</a>
 */
data class ConfigureFilamentChange(
    /** `T` - index */
    val index: Int? = null,
    /** `U` - pos */
    val pos: BigDecimal? = null,
    /** `L` - pos */
    val l: BigDecimal? = null,
) : GRQ<ConfigureFilamentChange> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (index != null) words.add(word('T', index))
        if (pos != null) words.add(word('U', pos.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        return M(603, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ConfigureFilamentChange> {

        override fun head(): GParameterWord<*> {
            return M(603).head
        }

        override fun decodeParams(params: List<GWord>): ConfigureFilamentChange {
            return ConfigureFilamentChange(
                index = params.intOf('T'),
                pos = params.decimalOf('U'),
                l = params.decimalOf('L'),
            )
        }
    }
}

/**
 * M605 S<value> [X<value>] [R<value>] [P<value>] [E<value>]
 *
 * Multi Nozzle Mode (control).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M605.html">MarlinFirmare M605 doc</a>
 */
data class MultiNozzleMode(
    /** `S` (required) */
    val s: Int? = null,
    /** `X` */
    val x: BigDecimal? = null,
    /** `R` */
    val r: Int? = null,
    /** `P` */
    val p: Int? = null,
    /** `E` */
    val e: Int? = null,
) : GRQ<MultiNozzleMode> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (s != null) words.add(word('S', s))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (r != null) words.add(word('R', r))
        if (p != null) words.add(word('P', p))
        if (e != null) words.add(word('E', e))
        return M(605, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<MultiNozzleMode> {

        override fun head(): GParameterWord<*> {
            return M(605).head
        }

        override fun decodeParams(params: List<GWord>): MultiNozzleMode {
            return MultiNozzleMode(
                s = params.intOf('S'),
                x = params.decimalOf('X'),
                r = params.intOf('R'),
                p = params.intOf('P'),
                e = params.intOf('E'),
            )
        }
    }
}

/**
 * M665 [S<segments-per-second>] [P<theta-pi-offset>] [T<theta-offset>] [A<theta-pi-offset>] [X<theta-pi-offset>] [B<theta-offset>] [Y<theta-offset>]
 *
 * SCARA Configuration.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M665.html">MarlinFirmare M665 doc</a>
 */
data class SCARAConfiguration(
    /** `S` - segments-per-second */
    val segmentsPerSecond: BigDecimal? = null,
    /** `P` - theta-pi-offset */
    val thetaPiOffset: BigDecimal? = null,
    /** `T` - theta-offset */
    val thetaOffset: BigDecimal? = null,
    /** `A` - theta-pi-offset */
    val a: BigDecimal? = null,
    /** `X` - theta-pi-offset */
    val x: BigDecimal? = null,
    /** `B` - theta-offset */
    val b: BigDecimal? = null,
    /** `Y` - theta-offset */
    val y: BigDecimal? = null,
) : GRQ<SCARAConfiguration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(7)
        if (segmentsPerSecond != null) words.add(word('S', segmentsPerSecond.toPlainString()))
        if (thetaPiOffset != null) words.add(word('P', thetaPiOffset.toPlainString()))
        if (thetaOffset != null) words.add(word('T', thetaOffset.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        return M(665, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SCARAConfiguration> {

        override fun head(): GParameterWord<*> {
            return M(665).head
        }

        override fun decodeParams(params: List<GWord>): SCARAConfiguration {
            return SCARAConfiguration(
                segmentsPerSecond = params.decimalOf('S'),
                thetaPiOffset = params.decimalOf('P'),
                thetaOffset = params.decimalOf('T'),
                a = params.decimalOf('A'),
                x = params.decimalOf('X'),
                b = params.decimalOf('B'),
                y = params.decimalOf('Y'),
            )
        }
    }
}

/**
 * M665 [H<linear>] [L<linear>] [R<linear>] [S<value>] [X<value>] [Y<value>] [Z<value>] [A<value>] [B<value>] [C<value>]
 *
 * Delta Configuration.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M665.html">MarlinFirmare M665 doc</a>
 */
data class DeltaConfiguration(
    /** `H` - linear */
    val linear: BigDecimal? = null,
    /** `L` - linear */
    val l: BigDecimal? = null,
    /** `R` - linear */
    val r: BigDecimal? = null,
    /** `S` */
    val s: BigDecimal? = null,
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
    /** `A` */
    val a: BigDecimal? = null,
    /** `B` */
    val b: BigDecimal? = null,
    /** `C` */
    val c: BigDecimal? = null,
) : GRQ<DeltaConfiguration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(10)
        if (linear != null) words.add(word('H', linear.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        return M(665, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<DeltaConfiguration> {

        override fun head(): GParameterWord<*> {
            return M(665).head
        }

        override fun decodeParams(params: List<GWord>): DeltaConfiguration {
            return DeltaConfiguration(
                linear = params.decimalOf('H'),
                l = params.decimalOf('L'),
                r = params.decimalOf('R'),
                s = params.decimalOf('S'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                a = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
            )
        }
    }
}

/**
 * M666 [X<adj>] [Y<adj>] [Z<adj>]
 *
 * Dual endstop offsets (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M666.html">MarlinFirmare M666 doc</a>
 */
data class DualEndstopOffsets(
    /** `X` - adj */
    val adj: BigDecimal? = null,
    /** `Y` - adj */
    val y: BigDecimal? = null,
    /** `Z` - adj */
    val z: BigDecimal? = null,
) : GRQ<DualEndstopOffsets> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (adj != null) words.add(word('X', adj.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(666, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<DualEndstopOffsets> {

        override fun head(): GParameterWord<*> {
            return M(666).head
        }

        override fun decodeParams(params: List<GWord>): DualEndstopOffsets {
            return DualEndstopOffsets(
                adj = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
            )
        }
    }
}

/**
 * M666 [X<adj>] [Y<adj>] [Z<adj>]
 *
 * Set Delta endstop adjustments (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M666.html">MarlinFirmare M666 doc</a>
 */
data class SetDeltaEndstopAdjustments(
    /** `X` - adj */
    val adj: BigDecimal? = null,
    /** `Y` - adj */
    val y: BigDecimal? = null,
    /** `Z` - adj */
    val z: BigDecimal? = null,
) : GRQ<SetDeltaEndstopAdjustments> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (adj != null) words.add(word('X', adj.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(666, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetDeltaEndstopAdjustments> {

        override fun head(): GParameterWord<*> {
            return M(666).head
        }

        override fun decodeParams(params: List<GWord>): SetDeltaEndstopAdjustments {
            return SetDeltaEndstopAdjustments(
                adj = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
            )
        }
    }
}

/**
 * M672
 *
 * Duet Smart Effector sensitivity (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M672.html">MarlinFirmare M672 doc</a>
 */
class DuetSmartEffectorSensitivity : GRQ<DuetSmartEffectorSensitivity> {

    override fun encode(): GCommand {
        return M(672)
    }

    override fun equals(other: Any?): Boolean {
        return other is DuetSmartEffectorSensitivity
    }

    override fun hashCode(): Int {
        return 274420
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<DuetSmartEffectorSensitivity> {

        override fun head(): GParameterWord<*> {
            return M(672).head
        }

        override fun decodeParams(params: List<GWord>): DuetSmartEffectorSensitivity {
            return DuetSmartEffectorSensitivity()
        }
    }
}

/**
 * M701 [T<extruder>] [Z<distance>] L<distance>
 *
 * Load filament (control).
 *
 * Marlin documents `L` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M701.html">MarlinFirmare M701 doc</a>
 */
data class LoadFilament(
    /** `T` - extruder */
    val extruder: Int? = null,
    /** `Z` - distance */
    val distance: BigDecimal? = null,
    /** `L` - distance (required) */
    val l: BigDecimal? = null,
) : GRQ<LoadFilament> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (extruder != null) words.add(word('T', extruder))
        if (distance != null) words.add(word('Z', distance.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        return M(701, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<LoadFilament> {

        override fun head(): GParameterWord<*> {
            return M(701).head
        }

        override fun decodeParams(params: List<GWord>): LoadFilament {
            return LoadFilament(
                extruder = params.intOf('T'),
                distance = params.decimalOf('Z'),
                l = params.decimalOf('L'),
            )
        }
    }
}

/**
 * M702 [T<extruder>] [Z<distance>] U<distance>
 *
 * Unload filament (control).
 *
 * Marlin documents `U` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M702.html">MarlinFirmare M702 doc</a>
 */
data class UnloadFilament(
    /** `T` - extruder */
    val extruder: Int? = null,
    /** `Z` - distance */
    val distance: BigDecimal? = null,
    /** `U` - distance (required) */
    val u: BigDecimal? = null,
) : GRQ<UnloadFilament> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (extruder != null) words.add(word('T', extruder))
        if (distance != null) words.add(word('Z', distance.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        return M(702, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<UnloadFilament> {

        override fun head(): GParameterWord<*> {
            return M(702).head
        }

        override fun decodeParams(params: List<GWord>): UnloadFilament {
            return UnloadFilament(
                extruder = params.intOf('T'),
                distance = params.decimalOf('Z'),
                u = params.decimalOf('U'),
            )
        }
    }
}

/**
 * M710 [S<speed>] [I<speed>] [A<value>] [R<value>] [D<seconds>]
 *
 * Controller Fan settings (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M710.html">MarlinFirmare M710 doc</a>
 */
data class ControllerFanSettings(
    /** `S` - speed */
    val speed: Int? = null,
    /** `I` - speed */
    val i: Int? = null,
    /** `A` */
    val a: Boolean? = null,
    /** `R` */
    val r: Boolean? = null,
    /** `D` - seconds */
    val seconds: Int? = null,
) : GRQ<ControllerFanSettings> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (speed != null) words.add(word('S', speed))
        if (i != null) words.add(word('I', i))
        if (a != null) words.add(word('A', if (a) 1 else 0))
        if (r != null) words.add(word('R', if (r) 1 else 0))
        if (seconds != null) words.add(word('D', seconds))
        return M(710, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ControllerFanSettings> {

        override fun head(): GParameterWord<*> {
            return M(710).head
        }

        override fun decodeParams(params: List<GWord>): ControllerFanSettings {
            return ControllerFanSettings(
                speed = params.intOf('S'),
                i = params.intOf('I'),
                a = params.boolOf('A'),
                r = params.boolOf('R'),
                seconds = params.intOf('D'),
            )
        }
    }
}

/**
 * M808 [L<value>]
 *
 * Repeat Marker.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M808.html">MarlinFirmare M808 doc</a>
 */
data class RepeatMarker(
    /** `L` */
    val l: Int? = null,
) : GRQ<RepeatMarker> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (l != null) words.add(word('L', l))
        return M(808, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<RepeatMarker> {

        override fun head(): GParameterWord<*> {
            return M(808).head
        }

        override fun decodeParams(params: List<GWord>): RepeatMarker {
            return RepeatMarker(
                l = params.intOf('L'),
            )
        }
    }
}

/**
 * M810
 *
 * G-code macros.
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M810.html">MarlinFirmare M810 doc</a>
 */
class GCodeMacrosM810 : GRQ<GCodeMacrosM810> {

    override fun encode(): GCommand {
        return M(810)
    }

    override fun equals(other: Any?): Boolean {
        return other is GCodeMacrosM810
    }

    override fun hashCode(): Int {
        return 753127
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GCodeMacrosM810> {

        override fun head(): GParameterWord<*> {
            return M(810).head
        }

        override fun decodeParams(params: List<GWord>): GCodeMacrosM810 {
            return GCodeMacrosM810()
        }
    }
}

/**
 * M811
 *
 * G-code macros.
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M811.html">MarlinFirmare M811 doc</a>
 */
class GCodeMacrosM811 : GRQ<GCodeMacrosM811> {

    override fun encode(): GCommand {
        return M(811)
    }

    override fun equals(other: Any?): Boolean {
        return other is GCodeMacrosM811
    }

    override fun hashCode(): Int {
        return 54001
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GCodeMacrosM811> {

        override fun head(): GParameterWord<*> {
            return M(811).head
        }

        override fun decodeParams(params: List<GWord>): GCodeMacrosM811 {
            return GCodeMacrosM811()
        }
    }
}

/**
 * M812
 *
 * G-code macros.
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M812.html">MarlinFirmare M812 doc</a>
 */
class GCodeMacrosM812 : GRQ<GCodeMacrosM812> {

    override fun encode(): GCommand {
        return M(812)
    }

    override fun equals(other: Any?): Boolean {
        return other is GCodeMacrosM812
    }

    override fun hashCode(): Int {
        return 963339
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GCodeMacrosM812> {

        override fun head(): GParameterWord<*> {
            return M(812).head
        }

        override fun decodeParams(params: List<GWord>): GCodeMacrosM812 {
            return GCodeMacrosM812()
        }
    }
}

/**
 * M813
 *
 * G-code macros.
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M813.html">MarlinFirmare M813 doc</a>
 */
class GCodeMacrosM813 : GRQ<GCodeMacrosM813> {

    override fun encode(): GCommand {
        return M(813)
    }

    override fun equals(other: Any?): Boolean {
        return other is GCodeMacrosM813
    }

    override fun hashCode(): Int {
        return 360285
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GCodeMacrosM813> {

        override fun head(): GParameterWord<*> {
            return M(813).head
        }

        override fun decodeParams(params: List<GWord>): GCodeMacrosM813 {
            return GCodeMacrosM813()
        }
    }
}

/**
 * M814
 *
 * G-code macros.
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M814.html">MarlinFirmare M814 doc</a>
 */
class GCodeMacrosM814 : GRQ<GCodeMacrosM814> {

    override fun encode(): GCommand {
        return M(814)
    }

    override fun equals(other: Any?): Boolean {
        return other is GCodeMacrosM814
    }

    override fun hashCode(): Int {
        return 522366
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GCodeMacrosM814> {

        override fun head(): GParameterWord<*> {
            return M(814).head
        }

        override fun decodeParams(params: List<GWord>): GCodeMacrosM814 {
            return GCodeMacrosM814()
        }
    }
}

/**
 * M815
 *
 * G-code macros.
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M815.html">MarlinFirmare M815 doc</a>
 */
class GCodeMacrosM815 : GRQ<GCodeMacrosM815> {

    override fun encode(): GCommand {
        return M(815)
    }

    override fun equals(other: Any?): Boolean {
        return other is GCodeMacrosM815
    }

    override fun hashCode(): Int {
        return 395688
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GCodeMacrosM815> {

        override fun head(): GParameterWord<*> {
            return M(815).head
        }

        override fun decodeParams(params: List<GWord>): GCodeMacrosM815 {
            return GCodeMacrosM815()
        }
    }
}

/**
 * M816
 *
 * G-code macros.
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M816.html">MarlinFirmare M816 doc</a>
 */
class GCodeMacrosM816 : GRQ<GCodeMacrosM816> {

    override fun encode(): GCommand {
        return M(816)
    }

    override fun equals(other: Any?): Boolean {
        return other is GCodeMacrosM816
    }

    override fun hashCode(): Int {
        return 897490
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GCodeMacrosM816> {

        override fun head(): GParameterWord<*> {
            return M(816).head
        }

        override fun decodeParams(params: List<GWord>): GCodeMacrosM816 {
            return GCodeMacrosM816()
        }
    }
}

/**
 * M817
 *
 * G-code macros.
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M817.html">MarlinFirmare M817 doc</a>
 */
class GCodeMacrosM817 : GRQ<GCodeMacrosM817> {

    override fun encode(): GCommand {
        return M(817)
    }

    override fun equals(other: Any?): Boolean {
        return other is GCodeMacrosM817
    }

    override fun hashCode(): Int {
        return 568644
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GCodeMacrosM817> {

        override fun head(): GParameterWord<*> {
            return M(817).head
        }

        override fun decodeParams(params: List<GWord>): GCodeMacrosM817 {
            return GCodeMacrosM817()
        }
    }
}

/**
 * M818
 *
 * G-code macros.
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M818.html">MarlinFirmare M818 doc</a>
 */
class GCodeMacrosM818 : GRQ<GCodeMacrosM818> {

    override fun encode(): GCommand {
        return M(818)
    }

    override fun equals(other: Any?): Boolean {
        return other is GCodeMacrosM818
    }

    override fun hashCode(): Int {
        return 370517
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GCodeMacrosM818> {

        override fun head(): GParameterWord<*> {
            return M(818).head
        }

        override fun decodeParams(params: List<GWord>): GCodeMacrosM818 {
            return GCodeMacrosM818()
        }
    }
}

/**
 * M819
 *
 * G-code macros.
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M819.html">MarlinFirmare M819 doc</a>
 */
class GCodeMacrosM819 : GRQ<GCodeMacrosM819> {

    override fun encode(): GCommand {
        return M(819)
    }

    override fun equals(other: Any?): Boolean {
        return other is GCodeMacrosM819
    }

    override fun hashCode(): Int {
        return 951555
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<GCodeMacrosM819> {

        override fun head(): GParameterWord<*> {
            return M(819).head
        }

        override fun decodeParams(params: List<GWord>): GCodeMacrosM819 {
            return GCodeMacrosM819()
        }
    }
}

/**
 * M820
 *
 * Report G-code macros.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M820.html">MarlinFirmare M820 doc</a>
 */
class ReportGCodeMacros : GRQ<ReportGCodeMacros> {

    override fun encode(): GCommand {
        return M(820)
    }

    override fun equals(other: Any?): Boolean {
        return other is ReportGCodeMacros
    }

    override fun hashCode(): Int {
        return 900254
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ReportGCodeMacros> {

        override fun head(): GParameterWord<*> {
            return M(820).head
        }

        override fun decodeParams(params: List<GWord>): ReportGCodeMacros {
            return ReportGCodeMacros()
        }
    }
}

/**
 * M851 [X<value>] [Y<value>] [Z<value>]
 *
 * XYZ Probe Offset.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M851.html">MarlinFirmare M851 doc</a>
 */
data class XYZProbeOffset(
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
) : GRQ<XYZProbeOffset> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(851, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<XYZProbeOffset> {

        override fun head(): GParameterWord<*> {
            return M(851).head
        }

        override fun decodeParams(params: List<GWord>): XYZProbeOffset {
            return XYZProbeOffset(
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
            )
        }
    }
}

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
) : GRQ<BedSkewCompensation> {

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

    companion object : GRQDecoder<BedSkewCompensation> {

        override fun head(): GParameterWord<*> {
            return M(852).head
        }

        override fun decodeParams(params: List<GWord>): BedSkewCompensation {
            return BedSkewCompensation(
                i = params.hasWord('I'),
                j = params.hasWord('J'),
                k = params.hasWord('K'),
                s = params.hasWord('S'),
            )
        }
    }
}

/**
 * M860 [I<index>] [O<value>] [X] [Y] [Z] [E] [U<value>] [P<value>] [S<addr>] [R<value>] [T<value>]
 *
 * I2C Position Encoders (encoder).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M860.html">MarlinFirmare M860 doc</a>
 */
data class I2CPositionEncodersM860(
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
) : GRQ<I2CPositionEncodersM860> {

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
        return M(860, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<I2CPositionEncodersM860> {

        override fun head(): GParameterWord<*> {
            return M(860).head
        }

        override fun decodeParams(params: List<GWord>): I2CPositionEncodersM860 {
            return I2CPositionEncodersM860(
                index = params.intOf('I'),
                o = params.boolOf('O'),
                axis = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                u = params.boolOf('U'),
                p = params.intOf('P'),
                addr = params.intOf('S'),
                r = params.boolOf('R'),
                t = params.decimalOf('T'),
            )
        }
    }
}

/**
 * M861 [I<index>] [O<value>] [X] [Y] [Z] [E] [U<value>] [P<value>] [S<addr>] [R<value>] [T<value>]
 *
 * I2C Position Encoders (encoder).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M861.html">MarlinFirmare M861 doc</a>
 */
data class I2CPositionEncodersM861(
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
) : GRQ<I2CPositionEncodersM861> {

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
        return M(861, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<I2CPositionEncodersM861> {

        override fun head(): GParameterWord<*> {
            return M(861).head
        }

        override fun decodeParams(params: List<GWord>): I2CPositionEncodersM861 {
            return I2CPositionEncodersM861(
                index = params.intOf('I'),
                o = params.boolOf('O'),
                axis = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                u = params.boolOf('U'),
                p = params.intOf('P'),
                addr = params.intOf('S'),
                r = params.boolOf('R'),
                t = params.decimalOf('T'),
            )
        }
    }
}

/**
 * M862 [I<index>] [O<value>] [X] [Y] [Z] [E] [U<value>] [P<value>] [S<addr>] [R<value>] [T<value>]
 *
 * I2C Position Encoders (encoder).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M862.html">MarlinFirmare M862 doc</a>
 */
data class I2CPositionEncodersM862(
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
) : GRQ<I2CPositionEncodersM862> {

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
        return M(862, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<I2CPositionEncodersM862> {

        override fun head(): GParameterWord<*> {
            return M(862).head
        }

        override fun decodeParams(params: List<GWord>): I2CPositionEncodersM862 {
            return I2CPositionEncodersM862(
                index = params.intOf('I'),
                o = params.boolOf('O'),
                axis = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                u = params.boolOf('U'),
                p = params.intOf('P'),
                addr = params.intOf('S'),
                r = params.boolOf('R'),
                t = params.decimalOf('T'),
            )
        }
    }
}

/**
 * M863 [I<index>] [O<value>] [X] [Y] [Z] [E] [U<value>] [P<value>] [S<addr>] [R<value>] [T<value>]
 *
 * I2C Position Encoders (encoder).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M863.html">MarlinFirmare M863 doc</a>
 */
data class I2CPositionEncodersM863(
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
) : GRQ<I2CPositionEncodersM863> {

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
        return M(863, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<I2CPositionEncodersM863> {

        override fun head(): GParameterWord<*> {
            return M(863).head
        }

        override fun decodeParams(params: List<GWord>): I2CPositionEncodersM863 {
            return I2CPositionEncodersM863(
                index = params.intOf('I'),
                o = params.boolOf('O'),
                axis = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                u = params.boolOf('U'),
                p = params.intOf('P'),
                addr = params.intOf('S'),
                r = params.boolOf('R'),
                t = params.decimalOf('T'),
            )
        }
    }
}

/**
 * M864 [I<index>] [O<value>] [X] [Y] [Z] [E] [U<value>] [P<value>] [S<addr>] [R<value>] [T<value>]
 *
 * I2C Position Encoders (encoder).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M864.html">MarlinFirmare M864 doc</a>
 */
data class I2CPositionEncodersM864(
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
) : GRQ<I2CPositionEncodersM864> {

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
        return M(864, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<I2CPositionEncodersM864> {

        override fun head(): GParameterWord<*> {
            return M(864).head
        }

        override fun decodeParams(params: List<GWord>): I2CPositionEncodersM864 {
            return I2CPositionEncodersM864(
                index = params.intOf('I'),
                o = params.boolOf('O'),
                axis = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                u = params.boolOf('U'),
                p = params.intOf('P'),
                addr = params.intOf('S'),
                r = params.boolOf('R'),
                t = params.decimalOf('T'),
            )
        }
    }
}

/**
 * M865 [I<index>] [O<value>] [X] [Y] [Z] [E] [U<value>] [P<value>] [S<addr>] [R<value>] [T<value>]
 *
 * I2C Position Encoders (encoder).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M865.html">MarlinFirmare M865 doc</a>
 */
data class I2CPositionEncodersM865(
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
) : GRQ<I2CPositionEncodersM865> {

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
        return M(865, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<I2CPositionEncodersM865> {

        override fun head(): GParameterWord<*> {
            return M(865).head
        }

        override fun decodeParams(params: List<GWord>): I2CPositionEncodersM865 {
            return I2CPositionEncodersM865(
                index = params.intOf('I'),
                o = params.boolOf('O'),
                axis = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                u = params.boolOf('U'),
                p = params.intOf('P'),
                addr = params.intOf('S'),
                r = params.boolOf('R'),
                t = params.decimalOf('T'),
            )
        }
    }
}

/**
 * M866 [I<index>] [O<value>] [X] [Y] [Z] [E] [U<value>] [P<value>] [S<addr>] [R<value>] [T<value>]
 *
 * I2C Position Encoders (encoder).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M866.html">MarlinFirmare M866 doc</a>
 */
data class I2CPositionEncodersM866(
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
) : GRQ<I2CPositionEncodersM866> {

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
        return M(866, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<I2CPositionEncodersM866> {

        override fun head(): GParameterWord<*> {
            return M(866).head
        }

        override fun decodeParams(params: List<GWord>): I2CPositionEncodersM866 {
            return I2CPositionEncodersM866(
                index = params.intOf('I'),
                o = params.boolOf('O'),
                axis = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                u = params.boolOf('U'),
                p = params.intOf('P'),
                addr = params.intOf('S'),
                r = params.boolOf('R'),
                t = params.decimalOf('T'),
            )
        }
    }
}

/**
 * M867 [I<index>] [O<value>] [X] [Y] [Z] [E] [U<value>] [P<value>] [S<addr>] [R<value>] [T<value>]
 *
 * I2C Position Encoders (encoder).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M867.html">MarlinFirmare M867 doc</a>
 */
data class I2CPositionEncodersM867(
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
) : GRQ<I2CPositionEncodersM867> {

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
        return M(867, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<I2CPositionEncodersM867> {

        override fun head(): GParameterWord<*> {
            return M(867).head
        }

        override fun decodeParams(params: List<GWord>): I2CPositionEncodersM867 {
            return I2CPositionEncodersM867(
                index = params.intOf('I'),
                o = params.boolOf('O'),
                axis = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                u = params.boolOf('U'),
                p = params.intOf('P'),
                addr = params.intOf('S'),
                r = params.boolOf('R'),
                t = params.decimalOf('T'),
            )
        }
    }
}

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
) : GRQ<I2CPositionEncodersM868> {

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

    companion object : GRQDecoder<I2CPositionEncodersM868> {

        override fun head(): GParameterWord<*> {
            return M(868).head
        }

        override fun decodeParams(params: List<GWord>): I2CPositionEncodersM868 {
            return I2CPositionEncodersM868(
                index = params.intOf('I'),
                o = params.boolOf('O'),
                axis = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                u = params.boolOf('U'),
                p = params.intOf('P'),
                addr = params.intOf('S'),
                r = params.boolOf('R'),
                t = params.decimalOf('T'),
            )
        }
    }
}

/**
 * M869 [I<index>] [O<value>] [X] [Y] [Z] [E] [U<value>] [P<value>] [S<addr>] [R<value>] [T<value>]
 *
 * I2C Position Encoders (encoder).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M869.html">MarlinFirmare M869 doc</a>
 */
data class I2CPositionEncodersM869(
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
) : GRQ<I2CPositionEncodersM869> {

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
        return M(869, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<I2CPositionEncodersM869> {

        override fun head(): GParameterWord<*> {
            return M(869).head
        }

        override fun decodeParams(params: List<GWord>): I2CPositionEncodersM869 {
            return I2CPositionEncodersM869(
                index = params.intOf('I'),
                o = params.boolOf('O'),
                axis = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
                u = params.boolOf('U'),
                p = params.intOf('P'),
                addr = params.intOf('S'),
                r = params.boolOf('R'),
                t = params.decimalOf('T'),
            )
        }
    }
}

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
) : GRQ<ProbeTemperatureConfig> {

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

    companion object : GRQDecoder<ProbeTemperatureConfig> {

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

/**
 * M876 S<response>
 *
 * Handle Prompt Response (hosts).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M876.html">MarlinFirmare M876 doc</a>
 */
data class HandlePromptResponse(
    /** `S` - response (required) */
    val response: Int? = null,
) : GRQ<HandlePromptResponse> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (response != null) words.add(word('S', response))
        return M(876, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<HandlePromptResponse> {

        override fun head(): GParameterWord<*> {
            return M(876).head
        }

        override fun decodeParams(params: List<GWord>): HandlePromptResponse {
            return HandlePromptResponse(
                response = params.intOf('S'),
            )
        }
    }
}

/**
 * M900 [K<kfactor>] [L<kfactor>] [S<slot>] [T<index>]
 *
 * Linear Advance Factor (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M900.html">MarlinFirmare M900 doc</a>
 */
data class LinearAdvanceFactor(
    /** `K` - kfactor */
    val kfactor: BigDecimal? = null,
    /** `L` - kfactor */
    val l: BigDecimal? = null,
    /** `S` - slot */
    val slot: Int? = null,
    /** `T` - index */
    val index: Int? = null,
) : GRQ<LinearAdvanceFactor> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (kfactor != null) words.add(word('K', kfactor.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (slot != null) words.add(word('S', slot))
        if (index != null) words.add(word('T', index))
        return M(900, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<LinearAdvanceFactor> {

        override fun head(): GParameterWord<*> {
            return M(900).head
        }

        override fun decodeParams(params: List<GWord>): LinearAdvanceFactor {
            return LinearAdvanceFactor(
                kfactor = params.decimalOf('K'),
                l = params.decimalOf('L'),
                slot = params.intOf('S'),
                index = params.intOf('T'),
            )
        }
    }
}

/**
 * M906 [E<value>] I<value> [T<value>] [X<value>] [Y<value>] [Z<value>]
 *
 * Stepper Motor Current.
 *
 * Marlin documents `I` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M906.html">MarlinFirmare M906 doc</a>
 */
data class StepperMotorCurrent(
    /** `E` */
    val e: Int? = null,
    /** `I` (required) */
    val i: Int? = null,
    /** `T` */
    val t: Int? = null,
    /** `X` */
    val x: Int? = null,
    /** `Y` */
    val y: Int? = null,
    /** `Z` */
    val z: Int? = null,
) : GRQ<StepperMotorCurrent> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (e != null) words.add(word('E', e))
        if (i != null) words.add(word('I', i))
        if (t != null) words.add(word('T', t))
        if (x != null) words.add(word('X', x))
        if (y != null) words.add(word('Y', y))
        if (z != null) words.add(word('Z', z))
        return M(906, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<StepperMotorCurrent> {

        override fun head(): GParameterWord<*> {
            return M(906).head
        }

        override fun decodeParams(params: List<GWord>): StepperMotorCurrent {
            return StepperMotorCurrent(
                e = params.intOf('E'),
                i = params.intOf('I'),
                t = params.intOf('T'),
                x = params.intOf('X'),
                y = params.intOf('Y'),
                z = params.intOf('Z'),
            )
        }
    }
}

/**
 * M907 [B<current>] [C<current>] [D<current>] [E<current>] [S<current>] [X<current>] [Y<current>] [Z<current>] [I<current>] [J<current>] [K<current>] [U<current>] [V<current>] [W<current>]
 *
 * Trimpot Stepper Motor Current (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M907.html">MarlinFirmare M907 doc</a>
 */
data class TrimpotStepperMotorCurrent(
    /** `B` - current */
    val current: BigDecimal? = null,
    /** `C` - current */
    val c: BigDecimal? = null,
    /** `D` - current */
    val d: BigDecimal? = null,
    /** `E` - current */
    val e: BigDecimal? = null,
    /** `S` - current */
    val s: BigDecimal? = null,
    /** `X` - current */
    val x: BigDecimal? = null,
    /** `Y` - current */
    val y: BigDecimal? = null,
    /** `Z` - current */
    val z: BigDecimal? = null,
    /** `I` - current */
    val i: BigDecimal? = null,
    /** `J` - current */
    val j: BigDecimal? = null,
    /** `K` - current */
    val k: BigDecimal? = null,
    /** `U` - current */
    val u: BigDecimal? = null,
    /** `V` - current */
    val v: BigDecimal? = null,
    /** `W` - current */
    val w: BigDecimal? = null,
) : GRQ<TrimpotStepperMotorCurrent> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(14)
        if (current != null) words.add(word('B', current.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (d != null) words.add(word('D', d.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (k != null) words.add(word('K', k.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        return M(907, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<TrimpotStepperMotorCurrent> {

        override fun head(): GParameterWord<*> {
            return M(907).head
        }

        override fun decodeParams(params: List<GWord>): TrimpotStepperMotorCurrent {
            return TrimpotStepperMotorCurrent(
                current = params.decimalOf('B'),
                c = params.decimalOf('C'),
                d = params.decimalOf('D'),
                e = params.decimalOf('E'),
                s = params.decimalOf('S'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                i = params.decimalOf('I'),
                j = params.decimalOf('J'),
                k = params.decimalOf('K'),
                u = params.decimalOf('U'),
                v = params.decimalOf('V'),
                w = params.decimalOf('W'),
            )
        }
    }
}

/**
 * M908 P<address> S<current>
 *
 * Set Trimpot Pins (control).
 *
 * Marlin documents `P`, `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M908.html">MarlinFirmare M908 doc</a>
 */
data class SetTrimpotPins(
    /** `P` - address (required) */
    val address: Int? = null,
    /** `S` - current (required) */
    val current: Int? = null,
) : GRQ<SetTrimpotPins> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (address != null) words.add(word('P', address))
        if (current != null) words.add(word('S', current))
        return M(908, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetTrimpotPins> {

        override fun head(): GParameterWord<*> {
            return M(908).head
        }

        override fun decodeParams(params: List<GWord>): SetTrimpotPins {
            return SetTrimpotPins(
                address = params.intOf('P'),
                current = params.intOf('S'),
            )
        }
    }
}

/**
 * M909
 *
 * Report DAC Stepper Current (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M909.html">MarlinFirmare M909 doc</a>
 */
class ReportDACStepperCurrent : GRQ<ReportDACStepperCurrent> {

    override fun encode(): GCommand {
        return M(909)
    }

    override fun equals(other: Any?): Boolean {
        return other is ReportDACStepperCurrent
    }

    override fun hashCode(): Int {
        return 323129
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ReportDACStepperCurrent> {

        override fun head(): GParameterWord<*> {
            return M(909).head
        }

        override fun decodeParams(params: List<GWord>): ReportDACStepperCurrent {
            return ReportDACStepperCurrent()
        }
    }
}

/**
 * M910
 *
 * Commit DAC to EEPROM (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M910.html">MarlinFirmare M910 doc</a>
 */
class CommitDACToEEPROM : GRQ<CommitDACToEEPROM> {

    override fun encode(): GCommand {
        return M(910)
    }

    override fun equals(other: Any?): Boolean {
        return other is CommitDACToEEPROM
    }

    override fun hashCode(): Int {
        return 456428
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<CommitDACToEEPROM> {

        override fun head(): GParameterWord<*> {
            return M(910).head
        }

        override fun decodeParams(params: List<GWord>): CommitDACToEEPROM {
            return CommitDACToEEPROM()
        }
    }
}

/**
 * M911
 *
 * TMC OT Pre-Warn Condition (trinamic).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M911.html">MarlinFirmare M911 doc</a>
 */
class TMCOTPreWarnCondition : GRQ<TMCOTPreWarnCondition> {

    override fun encode(): GCommand {
        return M(911)
    }

    override fun equals(other: Any?): Boolean {
        return other is TMCOTPreWarnCondition
    }

    override fun hashCode(): Int {
        return 479285
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<TMCOTPreWarnCondition> {

        override fun head(): GParameterWord<*> {
            return M(911).head
        }

        override fun decodeParams(params: List<GWord>): TMCOTPreWarnCondition {
            return TMCOTPreWarnCondition()
        }
    }
}

/**
 * M912 [I<value>] [X] [Y] [Z] [E<value>]
 *
 * Clear TMC OT Pre-Warn (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M912.html">MarlinFirmare M912 doc</a>
 */
data class ClearTMCOTPreWarn(
    /** `I` */
    val i: Int? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: BigDecimal? = null,
) : GRQ<ClearTMCOTPreWarn> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (i != null) words.add(word('I', i))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e != null) words.add(word('E', e.toPlainString()))
        return M(912, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<ClearTMCOTPreWarn> {

        override fun head(): GParameterWord<*> {
            return M(912).head
        }

        override fun decodeParams(params: List<GWord>): ClearTMCOTPreWarn {
            return ClearTMCOTPreWarn(
                i = params.intOf('I'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.decimalOf('E'),
            )
        }
    }
}

/**
 * M913 [I<value>] [T<value>] [X] [Y] [Z] [A<value>] [B<value>] [C<value>] [U<value>] [V<value>] [W<value>] [E]
 *
 * Set Hybrid Threshold Speed (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M913.html">MarlinFirmare M913 doc</a>
 */
data class SetHybridThresholdSpeed(
    /** `I` */
    val i: Int? = null,
    /** `T` */
    val t: Int? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `A` */
    val a: Int? = null,
    /** `B` */
    val b: Int? = null,
    /** `C` */
    val c: Int? = null,
    /** `U` */
    val u: Int? = null,
    /** `V` */
    val v: Int? = null,
    /** `W` */
    val w: Int? = null,
    /** `E` */
    val e: Boolean = false,
) : GRQ<SetHybridThresholdSpeed> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(12)
        if (i != null) words.add(word('I', i))
        if (t != null) words.add(word('T', t))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (a != null) words.add(word('A', a))
        if (b != null) words.add(word('B', b))
        if (c != null) words.add(word('C', c))
        if (u != null) words.add(word('U', u))
        if (v != null) words.add(word('V', v))
        if (w != null) words.add(word('W', w))
        if (e) words.add(flag('E'))
        return M(913, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<SetHybridThresholdSpeed> {

        override fun head(): GParameterWord<*> {
            return M(913).head
        }

        override fun decodeParams(params: List<GWord>): SetHybridThresholdSpeed {
            return SetHybridThresholdSpeed(
                i = params.intOf('I'),
                t = params.intOf('T'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                a = params.intOf('A'),
                b = params.intOf('B'),
                c = params.intOf('C'),
                u = params.intOf('U'),
                v = params.intOf('V'),
                w = params.intOf('W'),
                e = params.hasWord('E'),
            )
        }
    }
}

/**
 * M914 [I<value>] [X<value>] [Y<value>] [Z<value>] [A<value>] [B<value>] [C<value>] [U<value>] [V<value>] [W<value>]
 *
 * TMC Bump Sensitivity (trinamic).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M914.html">MarlinFirmare M914 doc</a>
 */
data class TMCBumpSensitivity(
    /** `I` */
    val i: Int? = null,
    /** `X` */
    val x: Int? = null,
    /** `Y` */
    val y: Int? = null,
    /** `Z` */
    val z: Int? = null,
    /** `A` */
    val a: Int? = null,
    /** `B` */
    val b: Int? = null,
    /** `C` */
    val c: Int? = null,
    /** `U` */
    val u: Int? = null,
    /** `V` */
    val v: Int? = null,
    /** `W` */
    val w: Int? = null,
) : GRQ<TMCBumpSensitivity> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(10)
        if (i != null) words.add(word('I', i))
        if (x != null) words.add(word('X', x))
        if (y != null) words.add(word('Y', y))
        if (z != null) words.add(word('Z', z))
        if (a != null) words.add(word('A', a))
        if (b != null) words.add(word('B', b))
        if (c != null) words.add(word('C', c))
        if (u != null) words.add(word('U', u))
        if (v != null) words.add(word('V', v))
        if (w != null) words.add(word('W', w))
        return M(914, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<TMCBumpSensitivity> {

        override fun head(): GParameterWord<*> {
            return M(914).head
        }

        override fun decodeParams(params: List<GWord>): TMCBumpSensitivity {
            return TMCBumpSensitivity(
                i = params.intOf('I'),
                x = params.intOf('X'),
                y = params.intOf('Y'),
                z = params.intOf('Z'),
                a = params.intOf('A'),
                b = params.intOf('B'),
                c = params.intOf('C'),
                u = params.intOf('U'),
                v = params.intOf('V'),
                w = params.intOf('W'),
            )
        }
    }
}

/**
 * M915 [S<value>] [Z<value>]
 *
 * TMC Z axis calibration (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M915.html">MarlinFirmare M915 doc</a>
 */
data class TMCZAxisCalibration(
    /** `S` */
    val s: Int? = null,
    /** `Z` */
    val z: BigDecimal? = null,
) : GRQ<TMCZAxisCalibration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (s != null) words.add(word('S', s))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(915, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<TMCZAxisCalibration> {

        override fun head(): GParameterWord<*> {
            return M(915).head
        }

        override fun decodeParams(params: List<GWord>): TMCZAxisCalibration {
            return TMCZAxisCalibration(
                s = params.intOf('S'),
                z = params.decimalOf('Z'),
            )
        }
    }
}

/**
 * M916 [J<value>] [X<value>] [Y<value>] [Z<value>] [E<value>] [F<feedrate>] [T<current>] [K<value>] [D<second>]
 *
 * L6474 Thermal Warning Test (L6474).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M916.html">MarlinFirmare M916 doc</a>
 */
data class L6474ThermalWarningTest(
    /** `J` */
    val j: Int? = null,
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
    /** `E` */
    val e: BigDecimal? = null,
    /** `F` - feedrate */
    val feedrate: Int? = null,
    /** `T` - current */
    val current: Int? = null,
    /** `K` */
    val k: Int? = null,
    /** `D` - second */
    val second: Int? = null,
) : GRQ<L6474ThermalWarningTest> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(9)
        if (j != null) words.add(word('J', j))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (feedrate != null) words.add(word('F', feedrate))
        if (current != null) words.add(word('T', current))
        if (k != null) words.add(word('K', k))
        if (second != null) words.add(word('D', second))
        return M(916, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<L6474ThermalWarningTest> {

        override fun head(): GParameterWord<*> {
            return M(916).head
        }

        override fun decodeParams(params: List<GWord>): L6474ThermalWarningTest {
            return L6474ThermalWarningTest(
                j = params.intOf('J'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                e = params.decimalOf('E'),
                feedrate = params.intOf('F'),
                current = params.intOf('T'),
                k = params.intOf('K'),
                second = params.intOf('D'),
            )
        }
    }
}

/**
 * M917 [J<value>] [X<value>] [Y<value>] [Z<value>] [A<value>] [B<value>] [C<value>] [U<value>] [V<value>] [W<value>] [E<value>] [F<feedrate>] [I<current>] [T<current>] [K<value>]
 *
 * L6474 Overcurrent Warning Test (L6474).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M917.html">MarlinFirmare M917 doc</a>
 */
data class L6474OvercurrentWarningTest(
    /** `J` */
    val j: Int? = null,
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
    /** `A` */
    val a: BigDecimal? = null,
    /** `B` */
    val b: BigDecimal? = null,
    /** `C` */
    val c: BigDecimal? = null,
    /** `U` */
    val u: BigDecimal? = null,
    /** `V` */
    val v: BigDecimal? = null,
    /** `W` */
    val w: BigDecimal? = null,
    /** `E` */
    val e: BigDecimal? = null,
    /** `F` - feedrate */
    val feedrate: Int? = null,
    /** `I` - current */
    val current: Int? = null,
    /** `T` - current */
    val t: Int? = null,
    /** `K` */
    val k: Int? = null,
) : GRQ<L6474OvercurrentWarningTest> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(15)
        if (j != null) words.add(word('J', j))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (feedrate != null) words.add(word('F', feedrate))
        if (current != null) words.add(word('I', current))
        if (t != null) words.add(word('T', t))
        if (k != null) words.add(word('K', k))
        return M(917, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<L6474OvercurrentWarningTest> {

        override fun head(): GParameterWord<*> {
            return M(917).head
        }

        override fun decodeParams(params: List<GWord>): L6474OvercurrentWarningTest {
            return L6474OvercurrentWarningTest(
                j = params.intOf('J'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                a = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                u = params.decimalOf('U'),
                v = params.decimalOf('V'),
                w = params.decimalOf('W'),
                e = params.decimalOf('E'),
                feedrate = params.intOf('F'),
                current = params.intOf('I'),
                t = params.intOf('T'),
                k = params.intOf('K'),
            )
        }
    }
}

/**
 * M918 [J<value>] [X<value>] [Y<value>] [Z<value>] [E<value>] [I<current>] [T<current>] [K<value>] [M<microsteps>]
 *
 * L6474 Speed Warning Test (L6474).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M918.html">MarlinFirmare M918 doc</a>
 */
data class L6474SpeedWarningTest(
    /** `J` */
    val j: Int? = null,
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
    /** `E` */
    val e: BigDecimal? = null,
    /** `I` - current */
    val current: Int? = null,
    /** `T` - current */
    val t: Int? = null,
    /** `K` */
    val k: Int? = null,
    /** `M` - microsteps */
    val microsteps: Int? = null,
) : GRQ<L6474SpeedWarningTest> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(9)
        if (j != null) words.add(word('J', j))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (current != null) words.add(word('I', current))
        if (t != null) words.add(word('T', t))
        if (k != null) words.add(word('K', k))
        if (microsteps != null) words.add(word('M', microsteps))
        return M(918, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<L6474SpeedWarningTest> {

        override fun head(): GParameterWord<*> {
            return M(918).head
        }

        override fun decodeParams(params: List<GWord>): L6474SpeedWarningTest {
            return L6474SpeedWarningTest(
                j = params.intOf('J'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                e = params.decimalOf('E'),
                current = params.intOf('I'),
                t = params.intOf('T'),
                k = params.intOf('K'),
                microsteps = params.intOf('M'),
            )
        }
    }
}

/**
 * M919 [O<value>] [P<value>] [S<value>] [I<value>] [T<value>] [X] [Y] [Z] [A] [B] [C] [U] [V] [W]
 *
 * TMC Chopper Timing (trinamic).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M919.html">MarlinFirmare M919 doc</a>
 */
data class TMCChopperTiming(
    /** `O` */
    val o: Int? = null,
    /** `P` */
    val p: Int? = null,
    /** `S` */
    val s: Int? = null,
    /** `I` */
    val i: Int? = null,
    /** `T` */
    val t: Int? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `A` */
    val a: Boolean = false,
    /** `B` */
    val b: Boolean = false,
    /** `C` */
    val c: Boolean = false,
    /** `U` */
    val u: Boolean = false,
    /** `V` */
    val v: Boolean = false,
    /** `W` */
    val w: Boolean = false,
) : GRQ<TMCChopperTiming> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(14)
        if (o != null) words.add(word('O', o))
        if (p != null) words.add(word('P', p))
        if (s != null) words.add(word('S', s))
        if (i != null) words.add(word('I', i))
        if (t != null) words.add(word('T', t))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (a) words.add(flag('A'))
        if (b) words.add(flag('B'))
        if (c) words.add(flag('C'))
        if (u) words.add(flag('U'))
        if (v) words.add(flag('V'))
        if (w) words.add(flag('W'))
        return M(919, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<TMCChopperTiming> {

        override fun head(): GParameterWord<*> {
            return M(919).head
        }

        override fun decodeParams(params: List<GWord>): TMCChopperTiming {
            return TMCChopperTiming(
                o = params.intOf('O'),
                p = params.intOf('P'),
                s = params.intOf('S'),
                i = params.intOf('I'),
                t = params.intOf('T'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                a = params.hasWord('A'),
                b = params.hasWord('B'),
                c = params.hasWord('C'),
                u = params.hasWord('U'),
                v = params.hasWord('V'),
                w = params.hasWord('W'),
            )
        }
    }
}

/**
 * M920 [I<value>] [X<value>] [Y<value>] [Z<value>] [A<value>] [B<value>] [C<value>] [U<value>] [V<value>] [W<value>]
 *
 * TMC Homing Current (trinamic).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M920.html">MarlinFirmare M920 doc</a>
 */
data class TMCHomingCurrent(
    /** `I` */
    val i: Int? = null,
    /** `X` */
    val x: Int? = null,
    /** `Y` */
    val y: Int? = null,
    /** `Z` */
    val z: Int? = null,
    /** `A` */
    val a: Int? = null,
    /** `B` */
    val b: Int? = null,
    /** `C` */
    val c: Int? = null,
    /** `U` */
    val u: Int? = null,
    /** `V` */
    val v: Int? = null,
    /** `W` */
    val w: Int? = null,
) : GRQ<TMCHomingCurrent> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(10)
        if (i != null) words.add(word('I', i))
        if (x != null) words.add(word('X', x))
        if (y != null) words.add(word('Y', y))
        if (z != null) words.add(word('Z', z))
        if (a != null) words.add(word('A', a))
        if (b != null) words.add(word('B', b))
        if (c != null) words.add(word('C', c))
        if (u != null) words.add(word('U', u))
        if (v != null) words.add(word('V', v))
        if (w != null) words.add(word('W', w))
        return M(920, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<TMCHomingCurrent> {

        override fun head(): GParameterWord<*> {
            return M(920).head
        }

        override fun decodeParams(params: List<GWord>): TMCHomingCurrent {
            return TMCHomingCurrent(
                i = params.intOf('I'),
                x = params.intOf('X'),
                y = params.intOf('Y'),
                z = params.intOf('Z'),
                a = params.intOf('A'),
                b = params.intOf('B'),
                c = params.intOf('C'),
                u = params.intOf('U'),
                v = params.intOf('V'),
                w = params.intOf('W'),
            )
        }
    }
}

/**
 * M928
 *
 * Start SD Logging (sdcard).
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M928.html">MarlinFirmare M928 doc</a>
 */
class StartSDLogging : GRQ<StartSDLogging> {

    override fun encode(): GCommand {
        return M(928)
    }

    override fun equals(other: Any?): Boolean {
        return other is StartSDLogging
    }

    override fun hashCode(): Int {
        return 48600
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<StartSDLogging> {

        override fun head(): GParameterWord<*> {
            return M(928).head
        }

        override fun decodeParams(params: List<GWord>): StartSDLogging {
            return StartSDLogging()
        }
    }
}

/**
 * M951 [L<value>] [R<value>] [I<value>] [J<value>] [H<value>] [D<value>] [C<value>]
 *
 * Magnetic Parking Extruder (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M951.html">MarlinFirmare M951 doc</a>
 */
data class MagneticParkingExtruder(
    /** `L` */
    val l: BigDecimal? = null,
    /** `R` */
    val r: BigDecimal? = null,
    /** `I` */
    val i: BigDecimal? = null,
    /** `J` */
    val j: BigDecimal? = null,
    /** `H` */
    val h: BigDecimal? = null,
    /** `D` */
    val d: BigDecimal? = null,
    /** `C` */
    val c: BigDecimal? = null,
) : GRQ<MagneticParkingExtruder> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(7)
        if (l != null) words.add(word('L', l.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (h != null) words.add(word('H', h.toPlainString()))
        if (d != null) words.add(word('D', d.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        return M(951, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<MagneticParkingExtruder> {

        override fun head(): GParameterWord<*> {
            return M(951).head
        }

        override fun decodeParams(params: List<GWord>): MagneticParkingExtruder {
            return MagneticParkingExtruder(
                l = params.decimalOf('L'),
                r = params.decimalOf('R'),
                i = params.decimalOf('I'),
                j = params.decimalOf('J'),
                h = params.decimalOf('H'),
                d = params.decimalOf('D'),
                c = params.decimalOf('C'),
            )
        }
    }
}

/**
 * M993
 *
 * Back up flash settings to SD (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M993.html">MarlinFirmare M993 doc</a>
 */
class BackUpFlashSettingsToSD : GRQ<BackUpFlashSettingsToSD> {

    override fun encode(): GCommand {
        return M(993)
    }

    override fun equals(other: Any?): Boolean {
        return other is BackUpFlashSettingsToSD
    }

    override fun hashCode(): Int {
        return 174573
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<BackUpFlashSettingsToSD> {

        override fun head(): GParameterWord<*> {
            return M(993).head
        }

        override fun decodeParams(params: List<GWord>): BackUpFlashSettingsToSD {
            return BackUpFlashSettingsToSD()
        }
    }
}

/**
 * M994
 *
 * Restore flash from SD (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M994.html">MarlinFirmare M994 doc</a>
 */
class RestoreFlashFromSD : GRQ<RestoreFlashFromSD> {

    override fun encode(): GCommand {
        return M(994)
    }

    override fun equals(other: Any?): Boolean {
        return other is RestoreFlashFromSD
    }

    override fun hashCode(): Int {
        return 538042
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<RestoreFlashFromSD> {

        override fun head(): GParameterWord<*> {
            return M(994).head
        }

        override fun decodeParams(params: List<GWord>): RestoreFlashFromSD {
            return RestoreFlashFromSD()
        }
    }
}

/**
 * M995
 *
 * Touch Screen Calibration (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M995.html">MarlinFirmare M995 doc</a>
 */
class TouchScreenCalibration : GRQ<TouchScreenCalibration> {

    override fun encode(): GCommand {
        return M(995)
    }

    override fun equals(other: Any?): Boolean {
        return other is TouchScreenCalibration
    }

    override fun hashCode(): Int {
        return 2877
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<TouchScreenCalibration> {

        override fun head(): GParameterWord<*> {
            return M(995).head
        }

        override fun decodeParams(params: List<GWord>): TouchScreenCalibration {
            return TouchScreenCalibration()
        }
    }
}

/**
 * M997
 *
 * Firmware update (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M997.html">MarlinFirmare M997 doc</a>
 */
class FirmwareUpdate : GRQ<FirmwareUpdate> {

    override fun encode(): GCommand {
        return M(997)
    }

    override fun equals(other: Any?): Boolean {
        return other is FirmwareUpdate
    }

    override fun hashCode(): Int {
        return 830502
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<FirmwareUpdate> {

        override fun head(): GParameterWord<*> {
            return M(997).head
        }

        override fun decodeParams(params: List<GWord>): FirmwareUpdate {
            return FirmwareUpdate()
        }
    }
}

/**
 * M999 [S<value>]
 *
 * STOP Restart (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M999.html">MarlinFirmare M999 doc</a>
 */
data class STOPRestart(
    /** `S` */
    val s: Boolean? = null,
) : GRQ<STOPRestart> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return M(999, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<STOPRestart> {

        override fun head(): GParameterWord<*> {
            return M(999).head
        }

        override fun decodeParams(params: List<GWord>): STOPRestart {
            return STOPRestart(
                s = params.boolOf('S'),
            )
        }
    }
}

/**
 * M7219 [C<column>] [D<row>] [R<row>] [I] [F] [P] [U<index>] [V<bits>] [X<index>] [Y<index>]
 *
 * MAX7219 Control (debug).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M7219.html">MarlinFirmare M7219 doc</a>
 */
data class MAX7219Control(
    /** `C` - column */
    val column: Int? = null,
    /** `D` - row */
    val row: Int? = null,
    /** `R` - row */
    val r: Int? = null,
    /** `I` */
    val i: Boolean = false,
    /** `F` */
    val f: Boolean = false,
    /** `P` */
    val p: Boolean = false,
    /** `U` - index */
    val index: Int? = null,
    /** `V` - bits */
    val bits: Long? = null,
    /** `X` - index */
    val x: Int? = null,
    /** `Y` - index */
    val y: Int? = null,
) : GRQ<MAX7219Control> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(10)
        if (column != null) words.add(word('C', column))
        if (row != null) words.add(word('D', row))
        if (r != null) words.add(word('R', r))
        if (i) words.add(flag('I'))
        if (f) words.add(flag('F'))
        if (p) words.add(flag('P'))
        if (index != null) words.add(word('U', index))
        if (bits != null) words.add(word('V', BigDecimal.valueOf(bits)))
        if (x != null) words.add(word('X', x))
        if (y != null) words.add(word('Y', y))
        return M(7219, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRQDecoder<MAX7219Control> {

        override fun head(): GParameterWord<*> {
            return M(7219).head
        }

        override fun decodeParams(params: List<GWord>): MAX7219Control {
            return MAX7219Control(
                column = params.intOf('C'),
                row = params.intOf('D'),
                r = params.intOf('R'),
                i = params.hasWord('I'),
                f = params.hasWord('F'),
                p = params.hasWord('P'),
                index = params.intOf('U'),
                bits = params.longOf('V'),
                x = params.intOf('X'),
                y = params.intOf('Y'),
            )
        }
    }
}
