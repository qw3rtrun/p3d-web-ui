package org.qw3rtrun.p3d.g.code.core.session

import org.qw3rtrun.p3d.g.code.core.block.CheckSumCalculator
import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.block.GLineLimit
import org.qw3rtrun.p3d.g.code.core.block.XorCheckSum
import org.qw3rtrun.p3d.g.code.core.command.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GLetter
import org.qw3rtrun.p3d.g.code.core.command.GParameterWord

/**
 * The host's side of GCODE_spec.md section 8.5: number and frame outgoing blocks, keep the ones
 * not yet acknowledged, and replay them when the firmware asks.
 *
 * An `ok` acknowledges queuing, not completion - Marlin buffers motion commands, so the window
 * holds a few blocks outstanding (`BUFSIZE`, 4 by default) while earlier ones are still executing.
 * It holds the exact emitted bytes rather than re-encoding, because spec 8.3 makes a checksum a
 * property of the bytes transmitted. No I/O: the caller sends what [send] returns, calls
 * [acknowledge] on an `ok`, and [resendFrom] on a `Resend:` line.
 *
 * Not thread-safe, and never will be - no atomics, so it ports to C. Every mutation ([send],
 * [acknowledge], [resendFrom], [reset]) must happen on the owner's one serialisation point;
 * `:backend:terminal`'s `CommandProcessor` drain loop is that point, and its atomic guard supplies
 * the happens-before these plain fields rely on. One window per session.
 *
 * A refused [send] consumes no line number: a gap is what the firmware rejects, so a full window
 * must not burn one.
 *
 * ```
 * val window = GSendWindow()
 * window.send(GCommand(GLetter('G'), GInt(28)))   // "N1 G28*18"
 * window.send("G90")                              // "N2 G90*18" - text form, same path
 * window.resendFrom(1)                            // ["N1 G28*18"] - after `Resend: 1`
 * window.acknowledge()                            // 1 - the block the `ok` released
 * ```
 *
 * @property capacity how many blocks may be outstanding at once; Marlin's `BUFSIZE` is 4
 * @property limit the firmware's receive buffer in bytes, spec 1.3; see [fits]
 * @property next the number the next block will carry
 * @property inFlight how many blocks are sent and not yet acknowledged
 */
class GSendWindow(
    val capacity: Int = 4,
    first: Int = 1,
    private val calculator: () -> CheckSumCalculator = ::XorCheckSum,
    val limit: Int = GLineLimit.MARLIN,
) {

    var next: Int = first
        private set

    private val outstanding = ArrayList<GSent>()

    val inFlight: Int
        get() = outstanding.size

    /**
     * Frames [command] as the next numbered block and records it, or null when the window is full -
     * the caller's signal to wait for an `ok` rather than an error. Length is not checked; see [fits].
     *
     * @param command the command to number, frame and hold
     * @return the framed block to transmit, or null when the window is full
     */
    fun send(command: GCommand): String? = send(GEncoder.encode(command))

    /**
     * The same, for a block already rendered to text - the entry point a transport uses. [payload]
     * is framed exactly as given and never inspected; see `GEncoder.frame` for what that leaves the
     * caller responsible for.
     *
     * @param payload the block's bytes, as they are to be transmitted, without an `N` or a `*`
     * @return the framed block to transmit, or null when the window is full
     */
    fun send(payload: String): String? {
        if (inFlight >= capacity) return null

        val text = GEncoder.frame(next, payload, calculator())
        outstanding.add(GSent(next, text))
        next++
        return text
    }

    /**
     * Whether [payload] would still fit the firmware's buffer once this window frames it - spec 1.3,
     * a check [send] deliberately does not make. Only this object can answer: the number is assigned
     * at send time, so the budget depends on the block about to go out next. Exact rather than
     * conservative - it frames the payload and measures the bytes; see `GLineLimit.payloadBudget`
     * for the conservative form used before a number exists.
     *
     * [send] never consults this: a refused send means "wait for an `ok`", but waiting cannot shorten
     * an over-long block, and sending it anyway is unrecoverable - spec 8.5 resend replays the exact
     * bytes, so a block truncated once is truncated identically on every resend. Failing the command
     * instead of queueing it is the caller's call to make.
     *
     * @param payload the block's bytes, without an `N` or a `*`
     * @return true when the framed block reaches the firmware whole
     */
    fun fits(payload: String): Boolean = GLineLimit.fits(GEncoder.frame(next, payload, calculator()), limit)

    /**
     * Records one `ok`: the oldest outstanding block is released. Returns its line number, or null
     * when nothing was outstanding (a stray `ok`). The number is the point: it is the only accurate
     * progress count, since counting blocks sent overstates after a [resendFrom]. For an `ok` that
     * names its line (Marlin's advanced `ok N5 ...`), use [acknowledge] with that number instead.
     *
     * @return the line number of the block released, or null for a stray `ok`
     */
    fun acknowledge(): Int? {
        if (outstanding.isEmpty()) return null
        return outstanding.removeAt(0).number
    }

    /**
     * The same, for an `ok` that names the line it acknowledges: releases [number] and everything
     * held before it, since spec 8.5 acknowledges in order. Prefer this whenever the number is on
     * the wire - unlike the counted [acknowledge], a lost or unknown `ok` here releases nothing
     * rather than silently shifting the correspondence by one. Two overloads rather than one default
     * argument, because a Kotlin default is invisible to `:backend:terminal`'s Java caller.
     *
     * @param number the line number the `ok` named
     * @return [number] when it was held, or null when it was not and nothing was released
     */
    fun acknowledge(number: Int): Int? {
        val at = indexOf(number)
        if (at < 0) return null
        for (i in 0..at) outstanding.removeAt(0)
        return number
    }

    /**
     * Whether [number] is still held and so can be replayed.
     *
     * @param number the line number a `Resend:` asked for
     * @return true when [resendFrom] would replay from [number]
     */
    fun canResendFrom(number: Int): Boolean = indexOf(number) >= 0

    /**
     * Spec 8.5 step 5: the blocks from [number] onward, in wire order, exactly as first sent. The
     * window rewinds to [number] - those blocks count as outstanding again - but numbering continues
     * after them, not from the rewind point.
     *
     * Empty when [number] is no longer held. That should not happen in a well-behaved session (`n`
     * is always one past a block the firmware has already accepted); a peer that asks anyway has
     * lost sync further than a replay can fix, and the answer is [reset], not a partial replay.
     *
     * @param number the line number from the firmware's `Resend:` request
     * @return the blocks to retransmit, byte for byte as first sent, or empty when [number] is not held
     */
    fun resendFrom(number: Int): List<String> {
        val from = indexOf(number)
        if (from < 0) return emptyList()

        val replay = ArrayList<String>(outstanding.size - from)
        for (i in from until outstanding.size) replay.add(outstanding[i].text)

        while (outstanding.size > 0 && outstanding[0].number < number) {
            outstanding.removeAt(0)
        }
        return replay
    }

    /**
     * Spec 7.2: emit `M110 N<to>` and renumber from there, to resynchronise when the sequence is
     * beyond repair. The `M110` block is itself numbered, but spec 7.2 exempts `M110` from the
     * continuity check, which is why this works at all. Everything outstanding is abandoned - once
     * the counter moves their numbers mean nothing.
     *
     * Always returns a block; the return type used to be nullable with nothing ever returning null.
     *
     * @param to the value the counter is set to; the next block sent carries `to + 1`
     * @return the framed `M110` block to transmit
     */
    fun reset(to: Int): String {
        val m110 = GCommand(
            GLetter('M'), GInt(110),
            listOf(GParameterWord(GLetter('N'), GInt(to)))
        )
        val text = GEncoder.frame(next, m110, calculator())

        outstanding.clear()
        next = to + 1
        return text
    }

    private fun indexOf(number: Int): Int {
        for (i in outstanding.indices) {
            if (outstanding[i].number == number) return i
        }
        return -1
    }
}

/**
 * One block this window sent and is still holding. A pair rather than parallel lists - a
 * portability hazard to keep in step, and a correctness one here since every operation removes
 * from the front of both.
 *
 * @property number the line number in the block's `N` field
 * @property text the framed block, byte for byte as it was transmitted
 */
internal data class GSent(val number: Int, val text: String)
