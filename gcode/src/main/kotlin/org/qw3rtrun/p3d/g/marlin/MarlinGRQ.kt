// GENERATED FILE - do not edit.
//
// Regenerate with:  python3 tools/marlin/gen_mcommands.py
// Source of truth:  doc/marlin-gcode/commands.json
// Extracted from:   MarlinFirmware/MarlinDocumentation @ 0856d12b0253378bc8d17a246fb09fc8c5437997
// How and why:      tools/marlin/README.md, doc/todos/11-marlin-commands.md
//
// Marlin's `G` commands, one class each, all implementing GRQ and all written the same
// way: `encode()` builds the command with the code/dsl builders, `head()` names it, and
// `decodeParams` reads one back. Every parameter is optional and absent by default, so a
// bare instance encodes to the bare command - `M105` and `M105 T0` are different commands
// and both have to be sayable.
//
// 54 classes over 48 distinct codes and 260 parameter slots. `G`, `M` and `T` are in three
// files only because there are 295 classes in all; MarlinRQ.kt registers every one of them
// and is the single place that sees all three. Generated rather than typed because a
// transposed parameter letter is invisible in review and shows up when a printer answers
// `echo:Unknown command`.

package org.qw3rtrun.p3d.g.marlin

import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.GRQ
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import java.math.BigDecimal

/**
 * G0 [X<pos>] [Y<pos>] [Z<pos>] [A<pos>] [B<pos>] [C<pos>] [U<pos>] [V<pos>] [W<pos>] [E<pos>] [F<rate>] [S<power>]
 *
 * Linear Move (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G0.html">MarlinFirmare G0 doc</a>
 */
data class LinearMoveG0(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `A` - pos */
    val a: BigDecimal? = null,
    /** `B` - pos */
    val b: BigDecimal? = null,
    /** `C` - pos */
    val c: BigDecimal? = null,
    /** `U` - pos */
    val u: BigDecimal? = null,
    /** `V` - pos */
    val v: BigDecimal? = null,
    /** `W` - pos */
    val w: BigDecimal? = null,
    /** `E` - pos */
    val e: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
    /** `S` - power */
    val power: BigDecimal? = null,
) : GRQ<LinearMoveG0> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(12)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        if (power != null) words.add(word('S', power.toPlainString()))
        return G(0, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(0).head
    }

    override fun decodeParams(params: List<GWord>): LinearMoveG0 {
        return LinearMoveG0(
            pos = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            z = params.decimalOf('Z'),
            a = params.decimalOf('A'),
            b = params.decimalOf('B'),
            c = params.decimalOf('C'),
            u = params.decimalOf('U'),
            v = params.decimalOf('V'),
            w = params.decimalOf('W'),
            e = params.decimalOf('E'),
            rate = params.decimalOf('F'),
            power = params.decimalOf('S'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G1 [X<pos>] [Y<pos>] [Z<pos>] [A<pos>] [B<pos>] [C<pos>] [U<pos>] [V<pos>] [W<pos>] [E<pos>] [F<rate>] [S<power>]
 *
 * Linear Move (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G1.html">MarlinFirmare G1 doc</a>
 */
data class LinearMoveG1(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `A` - pos */
    val a: BigDecimal? = null,
    /** `B` - pos */
    val b: BigDecimal? = null,
    /** `C` - pos */
    val c: BigDecimal? = null,
    /** `U` - pos */
    val u: BigDecimal? = null,
    /** `V` - pos */
    val v: BigDecimal? = null,
    /** `W` - pos */
    val w: BigDecimal? = null,
    /** `E` - pos */
    val e: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
    /** `S` - power */
    val power: BigDecimal? = null,
) : GRQ<LinearMoveG1> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(12)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        if (power != null) words.add(word('S', power.toPlainString()))
        return G(1, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(1).head
    }

    override fun decodeParams(params: List<GWord>): LinearMoveG1 {
        return LinearMoveG1(
            pos = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            z = params.decimalOf('Z'),
            a = params.decimalOf('A'),
            b = params.decimalOf('B'),
            c = params.decimalOf('C'),
            u = params.decimalOf('U'),
            v = params.decimalOf('V'),
            w = params.decimalOf('W'),
            e = params.decimalOf('E'),
            rate = params.decimalOf('F'),
            power = params.decimalOf('S'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G2 [X<pos>] [Y<pos>] [Z<pos>] [A<pos>] [B<pos>] [C<pos>] [U<pos>] [V<pos>] [W<pos>] I<offset> J<offset> R<radius> [E<pos>] [F<rate>] [P<count>] [S<power>]
 *
 * Arc or Circle Move (motion).
 *
 * Marlin documents `I`, `J`, `R` as required; every property here still defaults to
 * absent, because the decode registry needs a no-argument prototype.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G2.html">MarlinFirmare G2 doc</a>
 */
data class ArcOrCircleMoveG2(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `A` - pos */
    val a: BigDecimal? = null,
    /** `B` - pos */
    val b: BigDecimal? = null,
    /** `C` - pos */
    val c: BigDecimal? = null,
    /** `U` - pos */
    val u: BigDecimal? = null,
    /** `V` - pos */
    val v: BigDecimal? = null,
    /** `W` - pos */
    val w: BigDecimal? = null,
    /** `I` - offset (required) */
    val offset: BigDecimal? = null,
    /** `J` - offset (required) */
    val j: BigDecimal? = null,
    /** `R` - radius (required) */
    val radius: BigDecimal? = null,
    /** `E` - pos */
    val e: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
    /** `P` - count */
    val count: Int? = null,
    /** `S` - power */
    val power: BigDecimal? = null,
) : GRQ<ArcOrCircleMoveG2> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(16)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (offset != null) words.add(word('I', offset.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (radius != null) words.add(word('R', radius.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        if (count != null) words.add(word('P', count))
        if (power != null) words.add(word('S', power.toPlainString()))
        return G(2, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(2).head
    }

    override fun decodeParams(params: List<GWord>): ArcOrCircleMoveG2 {
        return ArcOrCircleMoveG2(
            pos = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            z = params.decimalOf('Z'),
            a = params.decimalOf('A'),
            b = params.decimalOf('B'),
            c = params.decimalOf('C'),
            u = params.decimalOf('U'),
            v = params.decimalOf('V'),
            w = params.decimalOf('W'),
            offset = params.decimalOf('I'),
            j = params.decimalOf('J'),
            radius = params.decimalOf('R'),
            e = params.decimalOf('E'),
            rate = params.decimalOf('F'),
            count = params.intOf('P'),
            power = params.decimalOf('S'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G3 [X<pos>] [Y<pos>] [Z<pos>] [A<pos>] [B<pos>] [C<pos>] [U<pos>] [V<pos>] [W<pos>] I<offset> J<offset> R<radius> [E<pos>] [F<rate>] [P<count>] [S<power>]
 *
 * Arc or Circle Move (motion).
 *
 * Marlin documents `I`, `J`, `R` as required; every property here still defaults to
 * absent, because the decode registry needs a no-argument prototype.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G3.html">MarlinFirmare G3 doc</a>
 */
data class ArcOrCircleMoveG3(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `A` - pos */
    val a: BigDecimal? = null,
    /** `B` - pos */
    val b: BigDecimal? = null,
    /** `C` - pos */
    val c: BigDecimal? = null,
    /** `U` - pos */
    val u: BigDecimal? = null,
    /** `V` - pos */
    val v: BigDecimal? = null,
    /** `W` - pos */
    val w: BigDecimal? = null,
    /** `I` - offset (required) */
    val offset: BigDecimal? = null,
    /** `J` - offset (required) */
    val j: BigDecimal? = null,
    /** `R` - radius (required) */
    val radius: BigDecimal? = null,
    /** `E` - pos */
    val e: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
    /** `P` - count */
    val count: Int? = null,
    /** `S` - power */
    val power: BigDecimal? = null,
) : GRQ<ArcOrCircleMoveG3> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(16)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (offset != null) words.add(word('I', offset.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (radius != null) words.add(word('R', radius.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        if (count != null) words.add(word('P', count))
        if (power != null) words.add(word('S', power.toPlainString()))
        return G(3, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(3).head
    }

    override fun decodeParams(params: List<GWord>): ArcOrCircleMoveG3 {
        return ArcOrCircleMoveG3(
            pos = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            z = params.decimalOf('Z'),
            a = params.decimalOf('A'),
            b = params.decimalOf('B'),
            c = params.decimalOf('C'),
            u = params.decimalOf('U'),
            v = params.decimalOf('V'),
            w = params.decimalOf('W'),
            offset = params.decimalOf('I'),
            j = params.decimalOf('J'),
            radius = params.decimalOf('R'),
            e = params.decimalOf('E'),
            rate = params.decimalOf('F'),
            count = params.intOf('P'),
            power = params.decimalOf('S'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G4 [S<time>] [P<time>]
 *
 * Dwell (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G4.html">MarlinFirmare G4 doc</a>
 */
data class Dwell(
    /** `S` - time */
    val time: Int? = null,
    /** `P` - time */
    val p: Int? = null,
) : GRQ<Dwell> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (time != null) words.add(word('S', time))
        if (p != null) words.add(word('P', p))
        return G(4, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(4).head
    }

    override fun decodeParams(params: List<GWord>): Dwell {
        return Dwell(
            time = params.intOf('S'),
            p = params.intOf('P'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G5 X<pos> Y<pos> [E<pos>] [F<rate>] I<pos> J<pos> P<pos> Q<pos> [S<power>]
 *
 * Bézier Cubic Spline Move (motion).
 *
 * Marlin documents `X`, `Y`, `I`, `J`, `P`, `Q` as required; every property here still defaults to
 * absent, because the decode registry needs a no-argument prototype.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G5.html">MarlinFirmare G5 doc</a>
 */
data class BezierCubicSplineMove(
    /** `X` - pos (required) */
    val pos: BigDecimal? = null,
    /** `Y` - pos (required) */
    val y: BigDecimal? = null,
    /** `E` - pos */
    val e: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
    /** `I` - pos (required) */
    val i: BigDecimal? = null,
    /** `J` - pos (required) */
    val j: BigDecimal? = null,
    /** `P` - pos (required) */
    val p: BigDecimal? = null,
    /** `Q` - pos (required) */
    val q: BigDecimal? = null,
    /** `S` - power */
    val power: BigDecimal? = null,
) : GRQ<BezierCubicSplineMove> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(9)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (p != null) words.add(word('P', p.toPlainString()))
        if (q != null) words.add(word('Q', q.toPlainString()))
        if (power != null) words.add(word('S', power.toPlainString()))
        return G(5, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(5).head
    }

    override fun decodeParams(params: List<GWord>): BezierCubicSplineMove {
        return BezierCubicSplineMove(
            pos = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            e = params.decimalOf('E'),
            rate = params.decimalOf('F'),
            i = params.decimalOf('I'),
            j = params.decimalOf('J'),
            p = params.decimalOf('P'),
            q = params.decimalOf('Q'),
            power = params.decimalOf('S'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G6 [I<index>] [R<rate>] [S<rate>] [X<direction>] [Y<direction>] [Z<direction>] [E<direction>]
 *
 * Direct Stepper Move (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G6.html">MarlinFirmare G6 doc</a>
 */
data class DirectStepperMove(
    /** `I` - index */
    val index: Int? = null,
    /** `R` - rate */
    val rate: BigDecimal? = null,
    /** `S` - rate */
    val s: BigDecimal? = null,
    /** `X` - direction */
    val direction: Int? = null,
    /** `Y` - direction */
    val y: Int? = null,
    /** `Z` - direction */
    val z: Int? = null,
    /** `E` - direction */
    val e: Int? = null,
) : GRQ<DirectStepperMove> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(7)
        if (index != null) words.add(word('I', index))
        if (rate != null) words.add(word('R', rate.toPlainString()))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (direction != null) words.add(word('X', direction))
        if (y != null) words.add(word('Y', y))
        if (z != null) words.add(word('Z', z))
        if (e != null) words.add(word('E', e))
        return G(6, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(6).head
    }

    override fun decodeParams(params: List<GWord>): DirectStepperMove {
        return DirectStepperMove(
            index = params.intOf('I'),
            rate = params.decimalOf('R'),
            s = params.decimalOf('S'),
            direction = params.intOf('X'),
            y = params.intOf('Y'),
            z = params.intOf('Z'),
            e = params.intOf('E'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G10 [S<value>]
 *
 * Retract (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G10.html">MarlinFirmare G10 doc</a>
 */
data class Retract(
    /** `S` */
    val s: Boolean? = null,
) : GRQ<Retract> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return G(10, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(10).head
    }

    override fun decodeParams(params: List<GWord>): Retract {
        return Retract(
            s = params.boolOf('S'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G11
 *
 * Recover (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G11.html">MarlinFirmare G11 doc</a>
 */
class Recover : GRQ<Recover> {

    override fun encode(): GCommand {
        return G(11)
    }

    override fun head(): GParameterWord<*> {
        return G(11).head
    }

    override fun decodeParams(params: List<GWord>): Recover {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is Recover
    }

    override fun hashCode(): Int {
        return 836631
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G12 [P<value>] [R<radius>] [S<count>] [T<count>] [X] [Y] [Z]
 *
 * Clean the Nozzle (nozzle).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G12.html">MarlinFirmare G12 doc</a>
 */
data class CleanTheNozzle(
    /** `P` */
    val p: Int? = null,
    /** `R` - radius */
    val radius: BigDecimal? = null,
    /** `S` - count */
    val count: Int? = null,
    /** `T` - count */
    val t: Int? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
) : GRQ<CleanTheNozzle> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(7)
        if (p != null) words.add(word('P', p))
        if (radius != null) words.add(word('R', radius.toPlainString()))
        if (count != null) words.add(word('S', count))
        if (t != null) words.add(word('T', t))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        return G(12, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(12).head
    }

    override fun decodeParams(params: List<GWord>): CleanTheNozzle {
        return CleanTheNozzle(
            p = params.intOf('P'),
            radius = params.decimalOf('R'),
            count = params.intOf('S'),
            t = params.intOf('T'),
            x = params.hasWord('X'),
            y = params.hasWord('Y'),
            z = params.hasWord('Z'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G17
 *
 * CNC Workspace Planes (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G17.html">MarlinFirmare G17 doc</a>
 */
class CNCWorkspacePlanesG17 : GRQ<CNCWorkspacePlanesG17> {

    override fun encode(): GCommand {
        return G(17)
    }

    override fun head(): GParameterWord<*> {
        return G(17).head
    }

    override fun decodeParams(params: List<GWord>): CNCWorkspacePlanesG17 {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is CNCWorkspacePlanesG17
    }

    override fun hashCode(): Int {
        return 793536
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G18
 *
 * CNC Workspace Planes (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G18.html">MarlinFirmare G18 doc</a>
 */
class CNCWorkspacePlanesG18 : GRQ<CNCWorkspacePlanesG18> {

    override fun encode(): GCommand {
        return G(18)
    }

    override fun head(): GParameterWord<*> {
        return G(18).head
    }

    override fun decodeParams(params: List<GWord>): CNCWorkspacePlanesG18 {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is CNCWorkspacePlanesG18
    }

    override fun hashCode(): Int {
        return 473086
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G19
 *
 * CNC Workspace Planes (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G19.html">MarlinFirmare G19 doc</a>
 */
class CNCWorkspacePlanesG19 : GRQ<CNCWorkspacePlanesG19> {

    override fun encode(): GCommand {
        return G(19)
    }

    override fun head(): GParameterWord<*> {
        return G(19).head
    }

    override fun decodeParams(params: List<GWord>): CNCWorkspacePlanesG19 {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is CNCWorkspacePlanesG19
    }

    override fun hashCode(): Int {
        return 185062
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G20
 *
 * Inch Units (units).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G20.html">MarlinFirmare G20 doc</a>
 */
class InchUnits : GRQ<InchUnits> {

    override fun encode(): GCommand {
        return G(20)
    }

    override fun head(): GParameterWord<*> {
        return G(20).head
    }

    override fun decodeParams(params: List<GWord>): InchUnits {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is InchUnits
    }

    override fun hashCode(): Int {
        return 357951
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G21
 *
 * Millimeter Units (units).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G21.html">MarlinFirmare G21 doc</a>
 */
class MillimeterUnits : GRQ<MillimeterUnits> {

    override fun encode(): GCommand {
        return G(21)
    }

    override fun head(): GParameterWord<*> {
        return G(21).head
    }

    override fun decodeParams(params: List<GWord>): MillimeterUnits {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is MillimeterUnits
    }

    override fun hashCode(): Int {
        return 112252
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G26 [B<temp>] [C<value>] [D] [F<linear>] [H<linear>] [I<index>] [K<value>] [L<linear>] [O<linear>] [P<linear>] [Q<value>] [R<value>] [S<value>] [U<linear>] [X<linear>] [Y<linear>]
 *
 * Mesh Validation Pattern (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G26.html">MarlinFirmare G26 doc</a>
 */
data class MeshValidationPattern(
    /** `B` - temp */
    val temp: Int? = null,
    /** `C` */
    val c: Boolean? = null,
    /** `D` */
    val d: Boolean = false,
    /** `F` - linear */
    val linear: BigDecimal? = null,
    /** `H` - linear */
    val h: BigDecimal? = null,
    /** `I` - index */
    val index: Int? = null,
    /** `K` */
    val k: Boolean? = null,
    /** `L` - linear */
    val l: BigDecimal? = null,
    /** `O` - linear */
    val o: BigDecimal? = null,
    /** `P` - linear */
    val p: BigDecimal? = null,
    /** `Q` */
    val q: BigDecimal? = null,
    /** `R` */
    val r: Int? = null,
    /** `S` */
    val s: BigDecimal? = null,
    /** `U` - linear */
    val u: BigDecimal? = null,
    /** `X` - linear */
    val x: BigDecimal? = null,
    /** `Y` - linear */
    val y: BigDecimal? = null,
) : GRQ<MeshValidationPattern> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(16)
        if (temp != null) words.add(word('B', temp))
        if (c != null) words.add(word('C', if (c) 1 else 0))
        if (d) words.add(flag('D'))
        if (linear != null) words.add(word('F', linear.toPlainString()))
        if (h != null) words.add(word('H', h.toPlainString()))
        if (index != null) words.add(word('I', index))
        if (k != null) words.add(word('K', if (k) 1 else 0))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (o != null) words.add(word('O', o.toPlainString()))
        if (p != null) words.add(word('P', p.toPlainString()))
        if (q != null) words.add(word('Q', q.toPlainString()))
        if (r != null) words.add(word('R', r))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        return G(26, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(26).head
    }

    override fun decodeParams(params: List<GWord>): MeshValidationPattern {
        return MeshValidationPattern(
            temp = params.intOf('B'),
            c = params.boolOf('C'),
            d = params.hasWord('D'),
            linear = params.decimalOf('F'),
            h = params.decimalOf('H'),
            index = params.intOf('I'),
            k = params.boolOf('K'),
            l = params.decimalOf('L'),
            o = params.decimalOf('O'),
            p = params.decimalOf('P'),
            q = params.decimalOf('Q'),
            r = params.intOf('R'),
            s = params.decimalOf('S'),
            u = params.decimalOf('U'),
            x = params.decimalOf('X'),
            y = params.decimalOf('Y'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G27 [P<value>]
 *
 * Park toolhead (nozzle).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G27.html">MarlinFirmare G27 doc</a>
 */
data class ParkToolhead(
    /** `P` */
    val p: Int? = null,
) : GRQ<ParkToolhead> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (p != null) words.add(word('P', p))
        return G(27, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(27).head
    }

    override fun decodeParams(params: List<GWord>): ParkToolhead {
        return ParkToolhead(
            p = params.intOf('P'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G28 [H] [L<value>] [O] [R<linear>] [X] [Y] [Z] [A] [B] [C] [U] [V] [W]
 *
 * Auto Home (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G28.html">MarlinFirmare G28 doc</a>
 */
data class AutoHome(
    /** `H` */
    val h: Boolean = false,
    /** `L` */
    val l: Boolean? = null,
    /** `O` */
    val o: Boolean = false,
    /** `R` - linear */
    val linear: BigDecimal? = null,
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
) : GRQ<AutoHome> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(13)
        if (h) words.add(flag('H'))
        if (l != null) words.add(word('L', if (l) 1 else 0))
        if (o) words.add(flag('O'))
        if (linear != null) words.add(word('R', linear.toPlainString()))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (a) words.add(flag('A'))
        if (b) words.add(flag('B'))
        if (c) words.add(flag('C'))
        if (u) words.add(flag('U'))
        if (v) words.add(flag('V'))
        if (w) words.add(flag('W'))
        return G(28, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(28).head
    }

    override fun decodeParams(params: List<GWord>): AutoHome {
        return AutoHome(
            h = params.hasWord('H'),
            l = params.boolOf('L'),
            o = params.hasWord('O'),
            linear = params.decimalOf('R'),
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

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G29 [A<value>] [C<value>] [O] [Q<value>] [E<value>] [D<value>] [J<value>] [V<value>]
 *
 * Bed Leveling (3-Point) (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G29.html">MarlinFirmare G29 doc</a>
 */
data class BedLeveling3Point(
    /** `A` */
    val a: Boolean? = null,
    /** `C` */
    val c: Boolean? = null,
    /** `O` */
    val o: Boolean = false,
    /** `Q` */
    val q: Boolean? = null,
    /** `E` */
    val e: Boolean? = null,
    /** `D` */
    val d: Boolean? = null,
    /** `J` */
    val j: Boolean? = null,
    /** `V` */
    val v: Int? = null,
) : GRQ<BedLeveling3Point> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (a != null) words.add(word('A', if (a) 1 else 0))
        if (c != null) words.add(word('C', if (c) 1 else 0))
        if (o) words.add(flag('O'))
        if (q != null) words.add(word('Q', if (q) 1 else 0))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        if (d != null) words.add(word('D', if (d) 1 else 0))
        if (j != null) words.add(word('J', if (j) 1 else 0))
        if (v != null) words.add(word('V', v))
        return G(29, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(29).head
    }

    override fun decodeParams(params: List<GWord>): BedLeveling3Point {
        return BedLeveling3Point(
            a = params.boolOf('A'),
            c = params.boolOf('C'),
            o = params.hasWord('O'),
            q = params.boolOf('Q'),
            e = params.boolOf('E'),
            d = params.boolOf('D'),
            j = params.boolOf('J'),
            v = params.intOf('V'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G29 [A<value>] [C<value>] [O] [Q<value>] [X<value>] [Y<value>] [Z<value>] [W<value>] [S<rate>] [E<value>] [D<value>] [H<linear>] [F<linear>] [B<linear>] [L<linear>] [R<linear>] [J<value>] [V<value>]
 *
 * Bed Leveling (Bilinear) (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G29.html">MarlinFirmare G29 doc</a>
 */
data class BedLevelingBilinear(
    /** `A` */
    val a: Boolean? = null,
    /** `C` */
    val c: Boolean? = null,
    /** `O` */
    val o: Boolean = false,
    /** `Q` */
    val q: Boolean? = null,
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
    /** `W` */
    val w: Boolean? = null,
    /** `S` - rate */
    val rate: BigDecimal? = null,
    /** `E` */
    val e: Boolean? = null,
    /** `D` */
    val d: Boolean? = null,
    /** `H` - linear */
    val linear: BigDecimal? = null,
    /** `F` - linear */
    val f: BigDecimal? = null,
    /** `B` - linear */
    val b: BigDecimal? = null,
    /** `L` - linear */
    val l: BigDecimal? = null,
    /** `R` - linear */
    val r: BigDecimal? = null,
    /** `J` */
    val j: Boolean? = null,
    /** `V` */
    val v: Int? = null,
) : GRQ<BedLevelingBilinear> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(18)
        if (a != null) words.add(word('A', if (a) 1 else 0))
        if (c != null) words.add(word('C', if (c) 1 else 0))
        if (o) words.add(flag('O'))
        if (q != null) words.add(word('Q', if (q) 1 else 0))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (w != null) words.add(word('W', if (w) 1 else 0))
        if (rate != null) words.add(word('S', rate.toPlainString()))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        if (d != null) words.add(word('D', if (d) 1 else 0))
        if (linear != null) words.add(word('H', linear.toPlainString()))
        if (f != null) words.add(word('F', f.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (j != null) words.add(word('J', if (j) 1 else 0))
        if (v != null) words.add(word('V', v))
        return G(29, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(29).head
    }

    override fun decodeParams(params: List<GWord>): BedLevelingBilinear {
        return BedLevelingBilinear(
            a = params.boolOf('A'),
            c = params.boolOf('C'),
            o = params.hasWord('O'),
            q = params.boolOf('Q'),
            x = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            z = params.decimalOf('Z'),
            w = params.boolOf('W'),
            rate = params.decimalOf('S'),
            e = params.boolOf('E'),
            d = params.boolOf('D'),
            linear = params.decimalOf('H'),
            f = params.decimalOf('F'),
            b = params.decimalOf('B'),
            l = params.decimalOf('L'),
            r = params.decimalOf('R'),
            j = params.boolOf('J'),
            v = params.intOf('V'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G29 [A<value>] [C<value>] [O] [Q<value>] [X<value>] [Y<value>] [P<value>] [S<rate>] [E<value>] [D<value>] [T<value>] [H<linear>] [F<linear>] [B<linear>] [L<linear>] [R<linear>] [J<value>] [V<value>]
 *
 * Bed Leveling (Linear) (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G29.html">MarlinFirmare G29 doc</a>
 */
data class BedLevelingLinear(
    /** `A` */
    val a: Boolean? = null,
    /** `C` */
    val c: Boolean? = null,
    /** `O` */
    val o: Boolean = false,
    /** `Q` */
    val q: Boolean? = null,
    /** `X` */
    val x: Int? = null,
    /** `Y` */
    val y: Int? = null,
    /** `P` */
    val p: Int? = null,
    /** `S` - rate */
    val rate: BigDecimal? = null,
    /** `E` */
    val e: Boolean? = null,
    /** `D` */
    val d: Boolean? = null,
    /** `T` */
    val t: Boolean? = null,
    /** `H` - linear */
    val linear: BigDecimal? = null,
    /** `F` - linear */
    val f: BigDecimal? = null,
    /** `B` - linear */
    val b: BigDecimal? = null,
    /** `L` - linear */
    val l: BigDecimal? = null,
    /** `R` - linear */
    val r: BigDecimal? = null,
    /** `J` */
    val j: Boolean? = null,
    /** `V` */
    val v: Int? = null,
) : GRQ<BedLevelingLinear> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(18)
        if (a != null) words.add(word('A', if (a) 1 else 0))
        if (c != null) words.add(word('C', if (c) 1 else 0))
        if (o) words.add(flag('O'))
        if (q != null) words.add(word('Q', if (q) 1 else 0))
        if (x != null) words.add(word('X', x))
        if (y != null) words.add(word('Y', y))
        if (p != null) words.add(word('P', p))
        if (rate != null) words.add(word('S', rate.toPlainString()))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        if (d != null) words.add(word('D', if (d) 1 else 0))
        if (t != null) words.add(word('T', if (t) 1 else 0))
        if (linear != null) words.add(word('H', linear.toPlainString()))
        if (f != null) words.add(word('F', f.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (j != null) words.add(word('J', if (j) 1 else 0))
        if (v != null) words.add(word('V', v))
        return G(29, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(29).head
    }

    override fun decodeParams(params: List<GWord>): BedLevelingLinear {
        return BedLevelingLinear(
            a = params.boolOf('A'),
            c = params.boolOf('C'),
            o = params.hasWord('O'),
            q = params.boolOf('Q'),
            x = params.intOf('X'),
            y = params.intOf('Y'),
            p = params.intOf('P'),
            rate = params.decimalOf('S'),
            e = params.boolOf('E'),
            d = params.boolOf('D'),
            t = params.boolOf('T'),
            linear = params.decimalOf('H'),
            f = params.decimalOf('F'),
            b = params.decimalOf('B'),
            l = params.decimalOf('L'),
            r = params.decimalOf('R'),
            j = params.boolOf('J'),
            v = params.intOf('V'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G29 S<value> [I<index>] [J<index>] [X<count>] [Y<count>] [Z<linear>]
 *
 * Bed Leveling (Manual) (calibration).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, because the decode registry needs a no-argument prototype.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G29.html">MarlinFirmare G29 doc</a>
 */
data class BedLevelingManual(
    /** `S` (required) */
    val s: Int? = null,
    /** `I` - index */
    val index: Int? = null,
    /** `J` - index */
    val j: Int? = null,
    /** `X` - count */
    val count: Int? = null,
    /** `Y` - count */
    val y: Int? = null,
    /** `Z` - linear */
    val linear: BigDecimal? = null,
) : GRQ<BedLevelingManual> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (s != null) words.add(word('S', s))
        if (index != null) words.add(word('I', index))
        if (j != null) words.add(word('J', j))
        if (count != null) words.add(word('X', count))
        if (y != null) words.add(word('Y', y))
        if (linear != null) words.add(word('Z', linear.toPlainString()))
        return G(29, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(29).head
    }

    override fun decodeParams(params: List<GWord>): BedLevelingManual {
        return BedLevelingManual(
            s = params.intOf('S'),
            index = params.intOf('I'),
            j = params.intOf('J'),
            count = params.intOf('X'),
            y = params.intOf('Y'),
            linear = params.decimalOf('Z'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G29 [A] [B<value>] [C<value>] [D] [E] [F<value>] [H<value>] [I<value>] [J<value>] [K<value>] [L<value>] [P<value>] [Q<value>] [R<value>] [S<slot>] [T<value>] [U] [V<value>] [W] [X<value>] [Y<value>]
 *
 * Bed Leveling (Unified) (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G29.html">MarlinFirmare G29 doc</a>
 */
data class BedLevelingUnified(
    /** `A` */
    val a: Boolean = false,
    /** `B` */
    val b: BigDecimal? = null,
    /** `C` */
    val c: BigDecimal? = null,
    /** `D` */
    val d: Boolean = false,
    /** `E` */
    val e: Boolean = false,
    /** `F` */
    val f: BigDecimal? = null,
    /** `H` */
    val h: BigDecimal? = null,
    /** `I` */
    val i: Int? = null,
    /** `J` */
    val j: Int? = null,
    /** `K` */
    val k: Int? = null,
    /** `L` */
    val l: Int? = null,
    /** `P` */
    val p: Int? = null,
    /** `Q` */
    val q: Int? = null,
    /** `R` */
    val r: Int? = null,
    /** `S` - slot */
    val slot: Int? = null,
    /** `T` */
    val t: Int? = null,
    /** `U` */
    val u: Boolean = false,
    /** `V` */
    val v: Int? = null,
    /** `W` */
    val w: Boolean = false,
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
) : GRQ<BedLevelingUnified> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(21)
        if (a) words.add(flag('A'))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (d) words.add(flag('D'))
        if (e) words.add(flag('E'))
        if (f != null) words.add(word('F', f.toPlainString()))
        if (h != null) words.add(word('H', h.toPlainString()))
        if (i != null) words.add(word('I', i))
        if (j != null) words.add(word('J', j))
        if (k != null) words.add(word('K', k))
        if (l != null) words.add(word('L', l))
        if (p != null) words.add(word('P', p))
        if (q != null) words.add(word('Q', q))
        if (r != null) words.add(word('R', r))
        if (slot != null) words.add(word('S', slot))
        if (t != null) words.add(word('T', t))
        if (u) words.add(flag('U'))
        if (v != null) words.add(word('V', v))
        if (w) words.add(flag('W'))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        return G(29, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(29).head
    }

    override fun decodeParams(params: List<GWord>): BedLevelingUnified {
        return BedLevelingUnified(
            a = params.hasWord('A'),
            b = params.decimalOf('B'),
            c = params.decimalOf('C'),
            d = params.hasWord('D'),
            e = params.hasWord('E'),
            f = params.decimalOf('F'),
            h = params.decimalOf('H'),
            i = params.intOf('I'),
            j = params.intOf('J'),
            k = params.intOf('K'),
            l = params.intOf('L'),
            p = params.intOf('P'),
            q = params.intOf('Q'),
            r = params.intOf('R'),
            slot = params.intOf('S'),
            t = params.intOf('T'),
            u = params.hasWord('U'),
            v = params.intOf('V'),
            w = params.hasWord('W'),
            x = params.decimalOf('X'),
            y = params.decimalOf('Y'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G29
 *
 * Bed Leveling (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G29.html">MarlinFirmare G29 doc</a>
 */
class BedLeveling : GRQ<BedLeveling> {

    override fun encode(): GCommand {
        return G(29)
    }

    override fun head(): GParameterWord<*> {
        return G(29).head
    }

    override fun decodeParams(params: List<GWord>): BedLeveling {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is BedLeveling
    }

    override fun hashCode(): Int {
        return 194606
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G30 [C<value>] [X<pos>] [Y<pos>] [E<value>]
 *
 * Single Z-Probe (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G30.html">MarlinFirmare G30 doc</a>
 */
data class SingleZProbe(
    /** `C` */
    val c: Boolean? = null,
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `E` */
    val e: Boolean? = null,
) : GRQ<SingleZProbe> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (c != null) words.add(word('C', if (c) 1 else 0))
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        return G(30, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(30).head
    }

    override fun decodeParams(params: List<GWord>): SingleZProbe {
        return SingleZProbe(
            c = params.boolOf('C'),
            pos = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            e = params.boolOf('E'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G31
 *
 * Dock Sled (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G31.html">MarlinFirmare G31 doc</a>
 */
class DockSled : GRQ<DockSled> {

    override fun encode(): GCommand {
        return G(31)
    }

    override fun head(): GParameterWord<*> {
        return G(31).head
    }

    override fun decodeParams(params: List<GWord>): DockSled {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is DockSled
    }

    override fun hashCode(): Int {
        return 337131
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G32
 *
 * Undock Sled (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G32.html">MarlinFirmare G32 doc</a>
 */
class UndockSled : GRQ<UndockSled> {

    override fun encode(): GCommand {
        return G(32)
    }

    override fun head(): GParameterWord<*> {
        return G(32).head
    }

    override fun decodeParams(params: List<GWord>): UndockSled {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is UndockSled
    }

    override fun hashCode(): Int {
        return 186536
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G33 [C<value>] [E<value>] [F<value>] [P<value>] [T<value>] [V<value>] [O<value>] [R<value>] [S] [X] [Y] [Z]
 *
 * Delta Auto Calibration ([ calibration, delta ]).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G33.html">MarlinFirmare G33 doc</a>
 */
data class DeltaAutoCalibration(
    /** `C` */
    val c: BigDecimal? = null,
    /** `E` */
    val e: Boolean? = null,
    /** `F` */
    val f: Int? = null,
    /** `P` */
    val p: Int? = null,
    /** `T` */
    val t: Boolean? = null,
    /** `V` */
    val v: Int? = null,
    /** `O` */
    val o: Boolean? = null,
    /** `R` */
    val r: BigDecimal? = null,
    /** `S` */
    val s: Boolean = false,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
) : GRQ<DeltaAutoCalibration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(12)
        if (c != null) words.add(word('C', c.toPlainString()))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        if (f != null) words.add(word('F', f))
        if (p != null) words.add(word('P', p))
        if (t != null) words.add(word('T', if (t) 1 else 0))
        if (v != null) words.add(word('V', v))
        if (o != null) words.add(word('O', if (o) 1 else 0))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (s) words.add(flag('S'))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        return G(33, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(33).head
    }

    override fun decodeParams(params: List<GWord>): DeltaAutoCalibration {
        return DeltaAutoCalibration(
            c = params.decimalOf('C'),
            e = params.boolOf('E'),
            f = params.intOf('F'),
            p = params.intOf('P'),
            t = params.boolOf('T'),
            v = params.intOf('V'),
            o = params.boolOf('O'),
            r = params.decimalOf('R'),
            s = params.hasWord('S'),
            x = params.hasWord('X'),
            y = params.hasWord('Y'),
            z = params.hasWord('Z'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G34 [S<value>] [Z<value>]
 *
 * Mechanical Gantry Calibration (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G34.html">MarlinFirmare G34 doc</a>
 */
data class MechanicalGantryCalibration(
    /** `S` */
    val s: Int? = null,
    /** `Z` */
    val z: BigDecimal? = null,
) : GRQ<MechanicalGantryCalibration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (s != null) words.add(word('S', s))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return G(34, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(34).head
    }

    override fun decodeParams(params: List<GWord>): MechanicalGantryCalibration {
        return MechanicalGantryCalibration(
            s = params.intOf('S'),
            z = params.decimalOf('Z'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G34 [L] [Z<value>] [S<value>] [I<value>] [T<value>] [A<value>] [E<value>] [R]
 *
 * Z Steppers Auto-Alignment (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G34.html">MarlinFirmare G34 doc</a>
 */
data class ZSteppersAutoAlignment(
    /** `L` */
    val l: Boolean = false,
    /** `Z` */
    val z: Int? = null,
    /** `S` */
    val s: Boolean? = null,
    /** `I` */
    val i: Int? = null,
    /** `T` */
    val t: BigDecimal? = null,
    /** `A` */
    val a: BigDecimal? = null,
    /** `E` */
    val e: Boolean? = null,
    /** `R` */
    val r: Boolean = false,
) : GRQ<ZSteppersAutoAlignment> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (l) words.add(flag('L'))
        if (z != null) words.add(word('Z', z))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        if (i != null) words.add(word('I', i))
        if (t != null) words.add(word('T', t.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        if (r) words.add(flag('R'))
        return G(34, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(34).head
    }

    override fun decodeParams(params: List<GWord>): ZSteppersAutoAlignment {
        return ZSteppersAutoAlignment(
            l = params.hasWord('L'),
            z = params.intOf('Z'),
            s = params.boolOf('S'),
            i = params.intOf('I'),
            t = params.decimalOf('T'),
            a = params.decimalOf('A'),
            e = params.boolOf('E'),
            r = params.hasWord('R'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G35 [S<value>]
 *
 * Tramming Assistant (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G35.html">MarlinFirmare G35 doc</a>
 */
data class TrammingAssistant(
    /** `S` */
    val s: Int? = null,
) : GRQ<TrammingAssistant> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (s != null) words.add(word('S', s))
        return G(35, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(35).head
    }

    override fun decodeParams(params: List<GWord>): TrammingAssistant {
        return TrammingAssistant(
            s = params.intOf('S'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G38.2 [X<pos>] [Y<pos>] [Z<pos>] [F<rate>]
 *
 * Probe target (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G38-2.html">MarlinFirmare G38.2 doc</a>
 */
data class ProbeTargetG38_2(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
) : GRQ<ProbeTargetG38_2> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        return G("38.2", *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G("38.2").head
    }

    override fun decodeParams(params: List<GWord>): ProbeTargetG38_2 {
        return ProbeTargetG38_2(
            pos = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            z = params.decimalOf('Z'),
            rate = params.decimalOf('F'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G38.3 [X<pos>] [Y<pos>] [Z<pos>] [F<rate>]
 *
 * Probe target (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G38-3.html">MarlinFirmare G38.3 doc</a>
 */
data class ProbeTargetG38_3(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
) : GRQ<ProbeTargetG38_3> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        return G("38.3", *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G("38.3").head
    }

    override fun decodeParams(params: List<GWord>): ProbeTargetG38_3 {
        return ProbeTargetG38_3(
            pos = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            z = params.decimalOf('Z'),
            rate = params.decimalOf('F'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G38.4 [X<pos>] [Y<pos>] [Z<pos>] [F<rate>]
 *
 * Probe target (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G38-4.html">MarlinFirmare G38.4 doc</a>
 */
data class ProbeTargetG38_4(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
) : GRQ<ProbeTargetG38_4> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        return G("38.4", *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G("38.4").head
    }

    override fun decodeParams(params: List<GWord>): ProbeTargetG38_4 {
        return ProbeTargetG38_4(
            pos = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            z = params.decimalOf('Z'),
            rate = params.decimalOf('F'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G38.5 [X<pos>] [Y<pos>] [Z<pos>] [F<rate>]
 *
 * Probe target (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G38-5.html">MarlinFirmare G38.5 doc</a>
 */
data class ProbeTargetG38_5(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
) : GRQ<ProbeTargetG38_5> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        return G("38.5", *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G("38.5").head
    }

    override fun decodeParams(params: List<GWord>): ProbeTargetG38_5 {
        return ProbeTargetG38_5(
            pos = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            z = params.decimalOf('Z'),
            rate = params.decimalOf('F'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G42 [I<pos>] [J<pos>] [F<rate>] [P]
 *
 * Move to mesh coordinate (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G42.html">MarlinFirmare G42 doc</a>
 */
data class MoveToMeshCoordinate(
    /** `I` - pos */
    val pos: BigDecimal? = null,
    /** `J` - pos */
    val j: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
    /** `P` */
    val p: Boolean = false,
) : GRQ<MoveToMeshCoordinate> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (pos != null) words.add(word('I', pos.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        if (p) words.add(flag('P'))
        return G(42, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(42).head
    }

    override fun decodeParams(params: List<GWord>): MoveToMeshCoordinate {
        return MoveToMeshCoordinate(
            pos = params.decimalOf('I'),
            j = params.decimalOf('J'),
            rate = params.decimalOf('F'),
            p = params.hasWord('P'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G53
 *
 * Move in Machine Coordinates (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G53.html">MarlinFirmare G53 doc</a>
 */
class MoveInMachineCoordinates : GRQ<MoveInMachineCoordinates> {

    override fun encode(): GCommand {
        return G(53)
    }

    override fun head(): GParameterWord<*> {
        return G(53).head
    }

    override fun decodeParams(params: List<GWord>): MoveInMachineCoordinates {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is MoveInMachineCoordinates
    }

    override fun hashCode(): Int {
        return 599181
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G54
 *
 * Select Workspace (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G54.html">MarlinFirmare G54 doc</a>
 */
class SelectWorkspaceG54 : GRQ<SelectWorkspaceG54> {

    override fun encode(): GCommand {
        return G(54)
    }

    override fun head(): GParameterWord<*> {
        return G(54).head
    }

    override fun decodeParams(params: List<GWord>): SelectWorkspaceG54 {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is SelectWorkspaceG54
    }

    override fun hashCode(): Int {
        return 991756
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G55
 *
 * Select Workspace (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G55.html">MarlinFirmare G55 doc</a>
 */
class SelectWorkspaceG55 : GRQ<SelectWorkspaceG55> {

    override fun encode(): GCommand {
        return G(55)
    }

    override fun head(): GParameterWord<*> {
        return G(55).head
    }

    override fun decodeParams(params: List<GWord>): SelectWorkspaceG55 {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is SelectWorkspaceG55
    }

    override fun hashCode(): Int {
        return 425820
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G56
 *
 * Select Workspace (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G56.html">MarlinFirmare G56 doc</a>
 */
class SelectWorkspaceG56 : GRQ<SelectWorkspaceG56> {

    override fun encode(): GCommand {
        return G(56)
    }

    override fun head(): GParameterWord<*> {
        return G(56).head
    }

    override fun decodeParams(params: List<GWord>): SelectWorkspaceG56 {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is SelectWorkspaceG56
    }

    override fun hashCode(): Int {
        return 565623
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G57
 *
 * Select Workspace (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G57.html">MarlinFirmare G57 doc</a>
 */
class SelectWorkspaceG57 : GRQ<SelectWorkspaceG57> {

    override fun encode(): GCommand {
        return G(57)
    }

    override fun head(): GParameterWord<*> {
        return G(57).head
    }

    override fun decodeParams(params: List<GWord>): SelectWorkspaceG57 {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is SelectWorkspaceG57
    }

    override fun hashCode(): Int {
        return 139315
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G58
 *
 * Select Workspace (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G58.html">MarlinFirmare G58 doc</a>
 */
class SelectWorkspaceG58 : GRQ<SelectWorkspaceG58> {

    override fun encode(): GCommand {
        return G(58)
    }

    override fun head(): GParameterWord<*> {
        return G(58).head
    }

    override fun decodeParams(params: List<GWord>): SelectWorkspaceG58 {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is SelectWorkspaceG58
    }

    override fun hashCode(): Int {
        return 180429
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G59
 *
 * Select Workspace (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G59.html">MarlinFirmare G59 doc</a>
 */
class SelectWorkspaceG59 : GRQ<SelectWorkspaceG59> {

    override fun encode(): GCommand {
        return G(59)
    }

    override fun head(): GParameterWord<*> {
        return G(59).head
    }

    override fun decodeParams(params: List<GWord>): SelectWorkspaceG59 {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is SelectWorkspaceG59
    }

    override fun hashCode(): Int {
        return 252860
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G59.1
 *
 * Select Workspace (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G59-1.html">MarlinFirmare G59.1 doc</a>
 */
class SelectWorkspaceG59_1 : GRQ<SelectWorkspaceG59_1> {

    override fun encode(): GCommand {
        return G("59.1")
    }

    override fun head(): GParameterWord<*> {
        return G("59.1").head
    }

    override fun decodeParams(params: List<GWord>): SelectWorkspaceG59_1 {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is SelectWorkspaceG59_1
    }

    override fun hashCode(): Int {
        return 978337
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G59.2
 *
 * Select Workspace (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G59-2.html">MarlinFirmare G59.2 doc</a>
 */
class SelectWorkspaceG59_2 : GRQ<SelectWorkspaceG59_2> {

    override fun encode(): GCommand {
        return G("59.2")
    }

    override fun head(): GParameterWord<*> {
        return G("59.2").head
    }

    override fun decodeParams(params: List<GWord>): SelectWorkspaceG59_2 {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is SelectWorkspaceG59_2
    }

    override fun hashCode(): Int {
        return 81480
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G59.3
 *
 * Select Workspace (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G59-3.html">MarlinFirmare G59.3 doc</a>
 */
class SelectWorkspaceG59_3 : GRQ<SelectWorkspaceG59_3> {

    override fun encode(): GCommand {
        return G("59.3")
    }

    override fun head(): GParameterWord<*> {
        return G("59.3").head
    }

    override fun decodeParams(params: List<GWord>): SelectWorkspaceG59_3 {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is SelectWorkspaceG59_3
    }

    override fun hashCode(): Int {
        return 869712
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G60 [S<slot>] [D<slot>] [Q<slot>] [F<rate>] [X<value>] [Y<value>] [Z<value>] [E<value>]
 *
 * Stored Positions (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G60.html">MarlinFirmare G60 doc</a>
 */
data class StoredPositions(
    /** `S` - slot */
    val slot: Int? = null,
    /** `D` - slot */
    val d: Int? = null,
    /** `Q` - slot */
    val q: Int? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
    /** `E` */
    val e: BigDecimal? = null,
) : GRQ<StoredPositions> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(8)
        if (slot != null) words.add(word('S', slot))
        if (d != null) words.add(word('D', d))
        if (q != null) words.add(word('Q', q))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        return G(60, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(60).head
    }

    override fun decodeParams(params: List<GWord>): StoredPositions {
        return StoredPositions(
            slot = params.intOf('S'),
            d = params.intOf('D'),
            q = params.intOf('Q'),
            rate = params.decimalOf('F'),
            x = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            z = params.decimalOf('Z'),
            e = params.decimalOf('E'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G61 [F<rate>] [S<slot>] [X<value>] [Y<value>] [Z<value>] [E<value>]
 *
 * Return to Saved Position (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G61.html">MarlinFirmare G61 doc</a>
 */
data class ReturnToSavedPosition(
    /** `F` - rate */
    val rate: BigDecimal? = null,
    /** `S` - slot */
    val slot: Int? = null,
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
    /** `E` */
    val e: BigDecimal? = null,
) : GRQ<ReturnToSavedPosition> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (rate != null) words.add(word('F', rate.toPlainString()))
        if (slot != null) words.add(word('S', slot))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        return G(61, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(61).head
    }

    override fun decodeParams(params: List<GWord>): ReturnToSavedPosition {
        return ReturnToSavedPosition(
            rate = params.decimalOf('F'),
            slot = params.intOf('S'),
            x = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            z = params.decimalOf('Z'),
            e = params.decimalOf('E'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G76 [B] [P]
 *
 * Probe temperature calibration (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G76.html">MarlinFirmare G76 doc</a>
 */
data class ProbeTemperatureCalibration(
    /** `B` */
    val b: Boolean = false,
    /** `P` */
    val p: Boolean = false,
) : GRQ<ProbeTemperatureCalibration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (b) words.add(flag('B'))
        if (p) words.add(flag('P'))
        return G(76, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(76).head
    }

    override fun decodeParams(params: List<GWord>): ProbeTemperatureCalibration {
        return ProbeTemperatureCalibration(
            b = params.hasWord('B'),
            p = params.hasWord('P'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G80
 *
 * Cancel Current Motion Mode (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G80.html">MarlinFirmare G80 doc</a>
 */
class CancelCurrentMotionMode : GRQ<CancelCurrentMotionMode> {

    override fun encode(): GCommand {
        return G(80)
    }

    override fun head(): GParameterWord<*> {
        return G(80).head
    }

    override fun decodeParams(params: List<GWord>): CancelCurrentMotionMode {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is CancelCurrentMotionMode
    }

    override fun hashCode(): Int {
        return 989195
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G90
 *
 * Absolute Positioning (units).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G90.html">MarlinFirmare G90 doc</a>
 */
class AbsolutePositioning : GRQ<AbsolutePositioning> {

    override fun encode(): GCommand {
        return G(90)
    }

    override fun head(): GParameterWord<*> {
        return G(90).head
    }

    override fun decodeParams(params: List<GWord>): AbsolutePositioning {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is AbsolutePositioning
    }

    override fun hashCode(): Int {
        return 404327
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G91
 *
 * Relative Positioning (units).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G91.html">MarlinFirmare G91 doc</a>
 */
class RelativePositioning : GRQ<RelativePositioning> {

    override fun encode(): GCommand {
        return G(91)
    }

    override fun head(): GParameterWord<*> {
        return G(91).head
    }

    override fun decodeParams(params: List<GWord>): RelativePositioning {
        return this
    }

    override fun equals(other: Any?): Boolean {
        return other is RelativePositioning
    }

    override fun hashCode(): Int {
        return 786502
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G92 [X<pos>] [Y<pos>] [Z<pos>] [A<pos>] [B<pos>] [C<pos>] [U<pos>] [V<pos>] [W<pos>] [E<pos>]
 *
 * Set Position (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G92.html">MarlinFirmare G92 doc</a>
 */
data class SetPosition(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `A` - pos */
    val a: BigDecimal? = null,
    /** `B` - pos */
    val b: BigDecimal? = null,
    /** `C` - pos */
    val c: BigDecimal? = null,
    /** `U` - pos */
    val u: BigDecimal? = null,
    /** `V` - pos */
    val v: BigDecimal? = null,
    /** `W` - pos */
    val w: BigDecimal? = null,
    /** `E` - pos */
    val e: BigDecimal? = null,
) : GRQ<SetPosition> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(10)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        return G(92, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(92).head
    }

    override fun decodeParams(params: List<GWord>): SetPosition {
        return SetPosition(
            pos = params.decimalOf('X'),
            y = params.decimalOf('Y'),
            z = params.decimalOf('Z'),
            a = params.decimalOf('A'),
            b = params.decimalOf('B'),
            c = params.decimalOf('C'),
            u = params.decimalOf('U'),
            v = params.decimalOf('V'),
            w = params.decimalOf('W'),
            e = params.decimalOf('E'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}

/**
 * G425 [B] [T<index>] [V] [U<linear>]
 *
 * Backlash and Toolhead Offset Calibration (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G425.html">MarlinFirmare G425 doc</a>
 */
data class BacklashAndToolheadOffsetCalibration(
    /** `B` */
    val b: Boolean = false,
    /** `T` - index */
    val index: Int? = null,
    /** `V` */
    val v: Boolean = false,
    /** `U` - linear */
    val linear: BigDecimal? = null,
) : GRQ<BacklashAndToolheadOffsetCalibration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (b) words.add(flag('B'))
        if (index != null) words.add(word('T', index))
        if (v) words.add(flag('V'))
        if (linear != null) words.add(word('U', linear.toPlainString()))
        return G(425, *words.toTypedArray())
    }

    override fun head(): GParameterWord<*> {
        return G(425).head
    }

    override fun decodeParams(params: List<GWord>): BacklashAndToolheadOffsetCalibration {
        return BacklashAndToolheadOffsetCalibration(
            b = params.hasWord('B'),
            index = params.intOf('T'),
            v = params.hasWord('V'),
            linear = params.decimalOf('U'),
        )
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }
}
