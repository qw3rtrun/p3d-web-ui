package org.qw3rtrun.p3d.g.protocol


/**
 * A reply the machine sends to the host: one line of the reply stream, typed, and an event for the
 * `:backend:core` pipeline.
 *
 * The reading direction of the protocol: `BaseRsDecoder` and `MarlinRsDecoder` turn a received line
 * into one of these, and [encode] writes it back as the line a machine would send.
 *
 * ```
 * ResendRs(123).encode()   // "Resend: 123"
 * ```
 */
interface GRs<T : GRs<T>> {

    /**
     * This reply as the line a machine would send, without a terminator. Where the protocol allows
     * several spellings, this is the canonical one.
     *
     * ```
     * SimpleOkRs.encode()                 // "ok"
     * AdvancedOkRs(15, 3, 100).encode()   // "ok P15 B3 N100"
     * ```
     *
     * @return the reply's text
     */
    fun encode(): String
}

/**
 * Reads one kind of reply off a received line. Every reply type's companion - or the reply object
 * itself, for one that carries nothing - implements it.
 *
 * [decode] is **the primitive**: one pass over the line, answering with the reply or with `null`,
 * because a reply stream mixes many kinds and a line that is some other kind is not an error.
 * [match] and [decodeParams] are derived from it.
 *
 * **It was the other way round, and the full work happened twice.** `match` and `decodeParams` were
 * the abstract members and `decode` was `if (match(line)) decodeParams(line) else null`, so every
 * line a decoder claimed was read, decided, and then read again - worst exactly where the work is
 * largest, and on the one path that runs for every line a printer sends. Two things fall out of the
 * inversion besides the pass:
 *
 * - **A decoder can no longer disagree with itself.** `decodeParams` re-validated with `require`
 *   after `match` had already said yes; the day the two disagreed, the result was an exception out
 *   of the middle of the transport's pipeline rather than the `null` it is written to handle.
 * - **The cheap boolean is gone**, and that is the trade. A caller that only wants [match] now pays
 *   for the whole read. The only callers that do are the partition tests in `MarlinRsTest` and
 *   `BaseRsTest`; if a hot one ever appears, the answer is a prefix check on that decoder, not a
 *   second full parse.
 *
 * ```
 * ResendRs.decode("rs N12")   // ResendRs(12)
 * ResendRs.decode("ok")       // null
 * ```
 */
interface GRsDecoder<out T : GRs<out T>> {

    /**
     * Reads [line] if it is this kind of reply, in one pass.
     *
     * ```
     * ResendRs.decode("rs:123")   // ResendRs(123)
     * ResendRs.decode("wait")     // null
     * ```
     *
     * @param line one received line
     * @return the typed reply, or null when [line] is some other kind
     */
    fun decode(line: String): T?

    /**
     * Whether [line] is this kind of reply - [decode] answering something rather than nothing.
     *
     * ```
     * ResendRs.match("Resend: 123")   // true
     * ResendRs.match("ok")            // false
     * ```
     *
     * @param line one received line, with or without surrounding whitespace
     * @return true when [decode] reads it
     */
    fun match(line: String): Boolean = decode(line) != null

    /**
     * Reads [line], which must be this kind of reply; throws `IllegalArgumentException` otherwise.
     *
     * For a caller that has already established the kind by some other means - a command decoder
     * whose head has resolved, a test naming the reply it wrote. Everything else wants [decode].
     *
     * ```
     * ResendRs.decodeParams("Resend: N123")   // ResendRs(123)
     * ResendRs.decodeParams("ok")             // throws IllegalArgumentException
     * ```
     *
     * @param line one received line of this kind
     * @return the typed reply
     */
    fun decodeParams(line: String): T =
        requireNotNull(decode(line)) { "not this kind of reply: $line" }
}

/**
 * A reply of the RepRap base protocol - the handshake a host drives its send loop by: `ok`,
 * `Resend`, errors, `wait`, `busy`, `start`, and the `//` lines.
 *
 * ```
 * BaseRsDecoder.decode("wait") is GProtoRs<*>   // true
 * ```
 */
interface GProtoRs<T : GProtoRs<T>> : GRs<T>

/**
 * A reply that reports machine state rather than driving the protocol: temperatures, positions,
 * firmware info, SD status, `echo:` lines.
 *
 * ```
 * MarlinRsDecoder.decode("echo:busy") is GEventRs<*>   // true
 * ```
 */
interface GEventRs<T : GEventRs<T>> : GRs<T>