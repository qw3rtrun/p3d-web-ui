package org.qw3rtrun.p3d.g.protocol

import org.qw3rtrun.p3d.core.msg.GEvent

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
interface GRs<T : GRs<T>> : GEvent {

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
 * [decode] is the entry point: a line that is not this kind of reply is `null`, never an error,
 * because a reply stream mixes many kinds. [decodeParams] assumes the line has already matched and
 * throws `IllegalArgumentException` when it has not.
 *
 * ```
 * ResendRs.decode("rs N12")   // ResendRs(12)
 * ResendRs.decode("ok")       // null
 * ```
 */
interface GRsDecoder<out T : GRs<out T>> {

    /**
     * Whether [line] is this kind of reply.
     *
     * ```
     * ResendRs.match("Resend: 123")   // true
     * ResendRs.match("ok")            // false
     * ```
     *
     * @param line one received line, with or without surrounding whitespace
     * @return true when [decodeParams] would read it
     */
    fun match(line: String): Boolean

    /**
     * Reads [line], which must already [match]; throws `IllegalArgumentException` otherwise.
     *
     * ```
     * ResendRs.decodeParams("Resend: N123")   // ResendRs(123)
     * ResendRs.decodeParams("ok")             // throws IllegalArgumentException
     * ```
     *
     * @param line one received line of this kind
     * @return the typed reply
     */
    fun decodeParams(line: String): T

    /**
     * Reads [line] if it is this kind of reply.
     *
     * ```
     * ResendRs.decode("rs:123")   // ResendRs(123)
     * ResendRs.decode("wait")     // null
     * ```
     *
     * @param line one received line
     * @return the typed reply, or null when [line] is some other kind
     */
    fun decode(line: String): T? {
        return if (match(line)) decodeParams(line) else null
    }
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