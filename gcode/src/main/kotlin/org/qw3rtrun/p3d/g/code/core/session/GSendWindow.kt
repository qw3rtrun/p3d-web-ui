package org.qw3rtrun.p3d.g.code.core.session

import org.qw3rtrun.p3d.g.code.core.CheckSumCalculator
import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.XorCheckSum
import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GLetter
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord

/**
 * The host's side of GCODE_spec.md section 8.5: number and frame outgoing lines, keep the ones that
 * have not been acknowledged, and replay them when the firmware asks.
 *
 * **The window exists because `ok` does not mean "done".** Motion commands are buffered and the
 * acknowledgement covers *queuing*, so a host that waited for each `ok` before sending the next line
 * would stall the planner between every move. It therefore keeps a few lines outstanding - Marlin's
 * `BUFSIZE`, 4 by default - and that is precisely the set it must be able to resend.
 *
 * **No I/O, on purpose.** This numbers, frames and remembers; it never writes to a port or reads a
 * reply. The caller sends what [send] returns, calls [acknowledge] when an `ok` arrives, and calls
 * [resendFrom] with the number in a `Resend:` line. Transport belongs to a different module, and
 * keeping it out is what lets the session travel with the rest of `code/core` to a port.
 *
 * It holds the **exact bytes** it emitted rather than the commands behind them: section 8.3 makes a
 * checksum a property of the bytes, so a replay that re-encoded could differ from what was
 * checksummed the first time.
 */
class GSendWindow(
    val capacity: Int = 4,
    first: Int = 1,
    private val calculator: () -> CheckSumCalculator = ::XorCheckSum,
) {

    /** The number the next line will carry. */
    var next: Int = first
        private set

    /** Sent and not yet acknowledged, oldest first, parallel to [outstandingText]. */
    private val outstandingNumber = ArrayList<Int>()
    private val outstandingText = ArrayList<String>()

    /** How many lines are in flight. */
    val inFlight: Int
        get() = outstandingNumber.size

    /**
     * Frames [command] as the next numbered line and records it, or returns **null** when the window
     * is full - which is the caller's signal to wait for an `ok` rather than an error.
     *
     * A refused send consumes no line number. It has to be that way round: a gap in the sequence is
     * exactly what the firmware rejects, so letting a full window burn a number would turn back
     * pressure into a protocol fault.
     */
    fun send(command: GCommand): String? {
        if (inFlight >= capacity) return null

        val text = GEncoder.frame(next, command, calculator())
        outstandingNumber.add(next)
        outstandingText.add(text)
        next++
        return text
    }

    /**
     * Records one `ok`: the oldest outstanding line is done and its slot is free. Returns false when
     * there was nothing outstanding, which is a stray acknowledgement rather than a fault.
     */
    fun acknowledge(): Boolean {
        if (outstandingNumber.isEmpty()) return false
        outstandingNumber.removeAt(0)
        outstandingText.removeAt(0)
        return true
    }

    /** Whether [number] is still held and so can be replayed. */
    fun canResendFrom(number: Int): Boolean = indexOf(number) >= 0

    /**
     * Spec 8.5 step 5: the lines from [number] onwards, in wire order, as they were originally sent.
     *
     * The window rewinds to [number], so everything replayed counts as outstanding again - it has
     * not been acknowledged, and a second failure must be able to ask for it again. Numbering
     * continues **after** the replayed lines, not from the rewind point: those lines keep the numbers
     * they were sent with.
     *
     * Returns an empty list when [number] is no longer held. In a well-behaved session that cannot
     * happen - `Resend: n` always has `n` one past a line the firmware accepted, and a host cannot
     * have seen an `ok` for a line that was never accepted, so `n` is never older than the oldest
     * outstanding line. A peer that asks anyway has lost sync further than a replay can repair, and
     * the answer is a fresh [reset], not a partial replay. Reported as a value rather than thrown,
     * because it is a statement about the session and not a bug in the caller.
     */
    fun resendFrom(number: Int): List<String> {
        val from = indexOf(number)
        if (from < 0) return emptyList()

        val replay = ArrayList<String>(outstandingText.size - from)
        for (i in from until outstandingText.size) replay.add(outstandingText[i])

        // Rewind: anything before `number` stays acknowledged, everything from it is in flight.
        while (outstandingNumber.size > 0 && outstandingNumber[0] < number) {
            outstandingNumber.removeAt(0)
            outstandingText.removeAt(0)
        }
        return replay
    }

    /**
     * Spec 7.2: emit `M110 N<to>` and renumber from there, which is how a host resynchronises when
     * the sequence is beyond repair.
     *
     * The `M110` line is itself numbered - with the number that was next - and a reader accepts it
     * whatever that number is, because spec 7.2 exempts `M110` from the continuity check. That
     * exemption is the whole reason a reset works at all.
     *
     * Everything outstanding is abandoned: after the counter moves, the numbers those lines carried
     * no longer mean anything, so there is nothing left to resend.
     */
    fun reset(to: Int): String? {
        val m110 = GCommand(
            GLetter('M'), GInt(110),
            listOf(GParameterWord(GLetter('N'), GInt(to)))
        )
        val text = GEncoder.frame(next, m110, calculator())

        outstandingNumber.clear()
        outstandingText.clear()
        next = to + 1
        return text
    }

    private fun indexOf(number: Int): Int {
        for (i in outstandingNumber.indices) {
            if (outstandingNumber[i] == number) return i
        }
        return -1
    }
}
