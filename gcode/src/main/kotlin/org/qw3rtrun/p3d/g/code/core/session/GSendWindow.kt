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
 * The host's side of GCODE_spec.md section 8.5: number and frame outgoing blocks, keep the ones that
 * have not been acknowledged, and replay them when the firmware asks.
 *
 * **The window exists because `ok` does not mean "done".** Motion commands are buffered and the
 * acknowledgement covers *queuing*, so a host that waited for each `ok` before sending the next block
 * would stall the planner between every move. It therefore keeps a few blocks outstanding - Marlin's
 * `BUFSIZE`, 4 by default - and that is precisely the set it must be able to resend.
 *
 * **No I/O, on purpose.** This numbers, frames and remembers; it never writes to a port or reads a
 * reply. The caller sends what [send] returns, calls [acknowledge] when an `ok` arrives, and calls
 * [resendFrom] with the number in a `Resend:` line. Transport belongs to a different module, and
 * keeping it out is what lets the session travel with the rest of `code/core` to a port.
 *
 * It holds the **exact bytes** it emitted rather than the commands behind them: section 8.3 makes a
 * checksum a property of the bytes, so a replay that re-encoded could differ from what was
 * checksummed the first time. The outstanding blocks are kept oldest first, as a list of [GSent].
 *
 * **Who may touch it: one owner, one serialisation point, and this is a contract rather than a
 * note.** There is not an atomic in this class and there is not going to be - it is portable Kotlin
 * and a port gets whatever its own host offers - so a window is **not thread-safe**, and every
 * mutation ([send], [acknowledge], [resendFrom], [reset]) must happen on whatever the owner has
 * chosen as its one point of serialisation. One window per session; never share one between two.
 *
 * That is not hypothetical. In this repo the three things that reach a window arrive on different
 * threads - a command is pulled on whichever thread produced it, an `ok` and a `Resend:` arrive on
 * the inbound loop - and `:backend:terminal`'s `CommandProcessor` resolves it by letting none of
 * them touch the window: each parks its work and then enters a drain loop whose guard admits one
 * thread at a time, and that thread is the only caller. The guard is an atomic, so the hand-off also
 * carries the happens-before these plain fields need. **That contract was written down on the
 * consumer and not here**, which is the wrong half - the consumer does not travel to a port and this
 * file does.
 *
 * **Bytes are also how a block gets in.** [send] takes either a `GCommand` or the text of one, and
 * the typed form is the text form applied to `GEncoder.encode(command)`: a sender that rendered its
 * block before queueing it - which is what a sender that numbers blocks last must do - can hand that
 * text straight over instead of parsing it back into a command.
 *
 * ```
 * val window = GSendWindow()
 * window.send(GCommand(GLetter('G'), GInt(28)))   // "N1 G28*18"
 * window.send("G90")                              // "N2 G90*18" - the same path, from text
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
     * Frames [command] as the next numbered block and records it, or returns **null** when the window
     * is full - which is the caller's signal to wait for an `ok` rather than an error.
     *
     * A refused send consumes no line number. It has to be that way round: a gap in the sequence is
     * exactly what the firmware rejects, so letting a full window burn a number would turn back
     * pressure into a protocol fault.
     *
     * **Null means full, and only that.** Length is not checked here - see [fits] for what that
     * leaves the caller to decide, and why an over-long block cannot share this channel.
     *
     * ```
     * val window = GSendWindow(capacity = 1)
     * window.send(GCommand(GLetter('G'), GInt(28)))   // "N1 G28*18"
     * window.send(GCommand(GLetter('G'), GInt(28)))   // null, and next is still 2
     * ```
     *
     * @param command the command to number, frame and hold
     * @return the framed block to transmit, or null when the window is full
     */
    fun send(command: GCommand): String? = send(GEncoder.encode(command))

    /**
     * The same, for a block a caller has **already rendered to text**: number it, frame it, hold it.
     *
     * This is the entry point a transport uses. A line number may only be assigned at the moment of
     * writing - anything that reorders blocks after numbering them produces a gap, and a gap is what
     * the firmware rejects - so by the time a sender reaches this window it is typically holding a
     * `String` and no longer a `GCommand`. Handing that string back through a parser to recover the
     * command would be a second reader of bytes this module already reads, and spec 8.3 does not need
     * one: framing is defined over the bytes, not over what they mean.
     *
     * [payload] is framed exactly as given and is never inspected; `GEncoder.frame` documents what
     * that leaves the caller responsible for. [send] over a `GCommand` is this function applied to
     * `GEncoder.encode(command)`, so both produce the same bytes and both are recorded the same way -
     * there is one framer and one place a sent block is remembered.
     *
     * ```
     * val window = GSendWindow()
     * window.send("G28")   // "N1 G28*18"
     * window.resendFrom(1) // ["N1 G28*18"]
     * ```
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
     * Whether [payload] would still fit the firmware's buffer **once this window has framed it** -
     * spec 1.3, and the check [send] deliberately does not make.
     *
     * **Only this object can answer it.** The framing is `N<number> ` in front and `*<checksum>`
     * behind, so how much room a payload has depends on the number it is about to be given, and the
     * number is assigned here, at the moment of writing, precisely because anything that reorders
     * blocks after numbering them produces a gap. A caller holding the text does not know it yet.
     * The answer is for the block that would go out **next**: send another first and it changes.
     *
     * Exact rather than conservative - it frames the payload and measures the bytes, this
     * algorithm's own checksum included. `GLineLimit.payloadBudget` is the conservative form, for a
     * generator deciding how much to put in a block before any number exists.
     *
     * **[send] does not consult this, and that is not an oversight.** A refused [send] returns null,
     * which the caller is told to read as "wait for an `ok`" - and the transport does exactly that,
     * parking the payload and retrying. An over-long block must never go down that path: waiting
     * will not shorten it. Nor can it simply be sent, because spec 8.5's recovery replays the exact
     * bytes, so a block the firmware truncates once is truncated identically on every resend and the
     * session stops making progress. It is the one error a resend cannot repair, which is why the
     * decision belongs to a caller that can fail the command instead of queueing it.
     *
     * ```
     * val window = GSendWindow()
     * window.fits("G28")                      // true
     * window.fits("M117 " + "x".repeat(90))   // false - 95 bytes bare, 99 or more framed
     * ```
     *
     * @param payload the block's bytes, without an `N` or a `*`
     * @return true when the framed block reaches the firmware whole
     */
    fun fits(payload: String): Boolean = GLineLimit.fits(GEncoder.frame(next, payload, calculator()), limit)

    /**
     * Records one `ok`: the oldest outstanding block is done and its slot is free. Returns **the
     * number of the block it released**, or null when there was nothing outstanding - a stray
     * acknowledgement rather than a fault.
     *
     * **The number is the point, and not a convenience.** A host that wants to report how far a
     * print has actually got has only two things it could count: the blocks it handed to the
     * transport, or the blocks the firmware accepted. The first is wrong in exactly the case that
     * matters - after a [resendFrom] the same blocks go out again, so a count of what was sent
     * overstates and then has to walk backwards - and the second is knowable only here, because
     * this is what holds the correspondence between an `ok` and the block it answers.
     *
     * **This is the form for a bare `ok`**, which is what most builds send and which carries nothing
     * to match on. When the acknowledgement names a line - Marlin's advanced `ok N5 P15 B3`, whose
     * number `AdvancedOkRs.lineNumber` already decodes - use [acknowledge] with it instead, and read
     * there why the difference matters.
     *
     * ```
     * val window = GSendWindow()
     * window.send(GCommand(GLetter('G'), GInt(28)))
     * window.acknowledge()   // 1 - the block that was just released
     * window.acknowledge()   // null - nothing was outstanding
     * ```
     *
     * @return the line number of the block released, or null for a stray `ok`
     */
    fun acknowledge(): Int? {
        if (outstanding.isEmpty()) return null
        return outstanding.removeAt(0).number
    }

    /**
     * The same, for an `ok` that **named the line it acknowledges**: release block [number] and
     * everything still held in front of it, or release nothing when this window is not holding it.
     *
     * Acknowledging block *n* accepts everything up to it and not only it. Spec 8.5 has the firmware
     * acknowledge in order, so an `ok N5` arriving while 3, 4 and 5 are outstanding says all three
     * were taken and the two earlier `ok`s were lost - not that 3 and 4 are somehow still pending.
     *
     * **Prefer this whenever the number is on the wire, because the counted form cannot
     * self-correct.** One `ok` lost in transit, or one arriving for a block this window never sent,
     * shifts [acknowledge]'s correspondence by one *permanently*; from then on every [resendFrom]
     * replays the wrong bytes, and does it silently, because an unknown number is answered with an
     * empty list rather than a complaint. Named, a stray `ok` releases nothing and the window does
     * not move - which is the whole reason this overload exists.
     *
     * **Two functions rather than one with a default**, which is what this was first written as: a
     * Kotlin default argument is not visible to a Java caller, so `acknowledge()` stopped compiling
     * in `:backend:terminal` - and a default argument is a portability wrinkle besides, since
     * neither C nor Rust has one. Two plain overloads transliterate as two functions.
     *
     * ```
     * val window = GSendWindow()
     * window.send("G28")       // "N1 G28*18"
     * window.send("G90")       // "N2 G90*18"
     * window.acknowledge(99)   // null - not a block this window holds, and nothing moves
     * window.acknowledge(2)    // 2 - and block 1 goes with it
     * ```
     *
     * @param number the line number the `ok` named
     * @return [number] when it was held - several blocks may have been released with it - or null
     *   when this window is not holding it, in which case nothing was released
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
     * ```
     * val window = GSendWindow()
     * window.send(GCommand(GLetter('G'), GInt(28)))
     * window.canResendFrom(1)   // true
     * ```
     *
     * @param number the line number a `Resend:` asked for
     * @return true when [resendFrom] would replay from [number]
     */
    fun canResendFrom(number: Int): Boolean = indexOf(number) >= 0

    /**
     * Spec 8.5 step 5: the blocks from [number] onwards, in wire order, as they were originally sent.
     *
     * The window rewinds to [number], so everything replayed counts as outstanding again - it has
     * not been acknowledged, and a second failure must be able to ask for it again. Numbering
     * continues **after** the replayed blocks, not from the rewind point: those blocks keep the numbers
     * they were sent with.
     *
     * Returns an empty list when [number] is no longer held. In a well-behaved session that cannot
     * happen - `Resend: n` always has `n` one past a block the firmware accepted, and a host cannot
     * have seen an `ok` for a block that was never accepted, so `n` is never older than the oldest
     * outstanding block. A peer that asks anyway has lost sync further than a replay can repair, and
     * the answer is a fresh [reset], not a partial replay. Reported as a value rather than thrown,
     * because it is a statement about the session and not a bug in the caller.
     *
     * On rewind, anything before [number] stays acknowledged and everything from it is in flight.
     *
     * ```
     * val window = GSendWindow()
     * window.send(GCommand(GLetter('G'), GInt(28)))
     * window.resendFrom(1)   // ["N1 G28*18"]
     * window.resendFrom(9)   // [] - not held
     * ```
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
     * Spec 7.2: emit `M110 N<to>` and renumber from there, which is how a host resynchronises when
     * the sequence is beyond repair.
     *
     * The `M110` block is itself numbered - with the number that was next - and a reader accepts it
     * whatever that number is, because spec 7.2 exempts `M110` from the continuity check. That
     * exemption is the whole reason a reset works at all.
     *
     * Everything outstanding is abandoned: after the counter moves, the numbers those blocks carried
     * no longer mean anything, so there is nothing left to resend.
     *
     * ```
     * val window = GSendWindow()
     * window.reset(0)   // "N1 M110 N0*124", and next is 1 again
     * ```
     *
     * The return type used to be nullable and nothing ever returned null, so it asked every caller
     * to handle a case that could not arise. A resynchronisation always has a block to send.
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
 * One block this window sent and is still holding: the line number it carries and the exact bytes.
 *
 * A pair rather than two parallel lists, which is what this replaced. Parallel arrays are a
 * portability hazard - a port has to re-establish by hand that they stay the same length - and a
 * correctness one here in particular, because every operation on this window removes from the front
 * of both and nothing but discipline kept them in step.
 *
 * @property number the line number in the block's `N` field
 * @property text the framed block, byte for byte as it was transmitted
 */
internal data class GSent(val number: Int, val text: String)
