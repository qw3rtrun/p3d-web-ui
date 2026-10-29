package org.qw3rtrun.p3d.g.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The decoder contract itself: a received line is read **once**.
 *
 * Every other decoder test asks what a line decodes *to*. This one asks what it costs, because the
 * cost was structural: `decode` was derived from `match` and `decodeParams`, so the full work
 * happened twice for every line a decoder claimed - worst exactly where the work is largest, and on
 * the one path that runs in production for every line a printer sends.
 *
 * It is written against a double rather than a real decoder because the claim is about the
 * interface, not about any one reply: a decoder that reads the line twice satisfies every
 * behavioural test in the module and fails this one.
 */
class GRsDecoderTest {

    private data class CountedRs(val text: String) : GProtoRs<CountedRs> {
        override fun encode(): String = text
    }

    /**
     * A decoder that counts how many times it reads the line it is given.
     *
     * It implements whatever the interface's **primitive** is - one method, doing the whole job -
     * so the count is the number of passes the interface makes over the line, not a property of
     * this class.
     */
    private class Counting : GRsDecoder<CountedRs> {

        var reads = 0
            private set

        override fun decode(line: String): CountedRs? {
            reads++
            return if (line.startsWith("count")) CountedRs(line) else null
        }
    }

    @Test
    fun `decode reads the line once`() {
        val decoder = Counting()

        assertEquals(CountedRs("count:1"), decoder.decode("count:1"))
        assertEquals(1, decoder.reads, "decode made more than one pass over the line")
    }

    @Test
    fun `a line the decoder does not claim costs one pass and yields absence`() {
        val decoder = Counting()

        assertNull(decoder.decode("ok"))
        assertEquals(1, decoder.reads, "a declined line cost more than one pass")
    }

    /**
     * `match` and `decodeParams` still answer, and they answer *through* `decode`.
     *
     * They are kept because the partition tests in `MarlinRsTest` and `BaseRsTest` ask "is this
     * line claimed" of every decoder, and because `decodeParams` is what a generated command
     * decoder calls once a head has already resolved. A caller that only wanted the boolean now
     * pays for the whole read - which is the trade this inversion makes, and the only callers that
     * take it are tests.
     */
    @Test
    fun `match and decodeParams are derived from decode`() {
        val decoder = Counting()

        assertTrue(decoder.match("count:1"))
        assertEquals(1, decoder.reads)

        assertEquals(CountedRs("count:2"), decoder.decodeParams("count:2"))
        assertEquals(2, decoder.reads)
    }

    /**
     * A decoder cannot disagree with itself about a line any more.
     *
     * `decodeParams` used to re-validate with `require` after `match` had already said yes, so the
     * day the two disagreed the result was an exception thrown out of the middle of a Reactor
     * pipeline rather than the `null` the transport is written to handle. Derived from one read,
     * the disagreement is unrepresentable; what is left is the honest case - a caller calling
     * `decodeParams` on a line that simply is not this reply.
     */
    @Test
    fun `decodeParams on a line that is not this reply throws rather than inventing one`() {
        val decoder = Counting()

        val failure = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            decoder.decodeParams("ok")
        }
        assertTrue(failure.message!!.contains("ok"), failure.message)
    }
}
