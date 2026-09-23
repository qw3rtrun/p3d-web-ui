package org.qw3rtrun.p3d.g.protocol

import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GToken
import org.qw3rtrun.p3d.g.code.core.token.headEnd
import org.qw3rtrun.p3d.g.code.core.token.headKey
import org.qw3rtrun.p3d.g.code.core.token.headWord

/**
 * A request this host can send: one typed command that knows how to write itself.
 *
 * The writing direction has the semantics in hand - the caller holds a `SetHotendTemperature`, so
 * what every parameter means is already decided - and that is why [encode] may produce a
 * [GCommand]. Reading is the other way round and is [GRqDecoder]'s job.
 */
interface GRq<T : GRq<T>> {
    fun encode(): GCommand
}

/**
 * The reading half of a command: tokens in, one typed request out.
 *
 * **Decoding takes tokens, not words.** Splitting a line into [org.qw3rtrun.p3d.g.code.core.token.GWord]s
 * - and so into a [GCommand] - is already an interpretation, and it is one the token layer is not
 * entitled to make: whether `M117 Hello World` carries two flag words or one unquoted string
 * depends on the command number alone (see `GUnquotedString`), and nothing below this interface
 * knows the command number. A decoder does. So it is handed the tokens as lexed and decides for
 * itself what they mean - which is also what lets a command with a rest-of-line argument be read at
 * all, rather than being mis-split before it ever reaches its decoder.
 */
interface GRqDecoder<out T : GRq<out T>> {

    /** The command word this decoder answers to, canonically spelled: `G1`, `M104`, `G38.2`. */
    fun head(): GParameterWord<*>

    /**
     * The command [tokens] spell, reading them as this command's parameters.
     *
     * [tokens] are what followed the head, in wire order and exactly as lexed - separators and
     * comments included, since for some commands those are part of the argument. The head is not
     * among them; [decode] is what strips it.
     *
     * **A `List`, and that is the contract rather than a convenience.** Every parameter is its own
     * scan of these tokens - one per letter - so what a decoder needs is something it may walk
     * repeatedly. This took a `Sequence` and documented that a single-use one would throw halfway
     * through a command, which is an API describing its own hazard instead of ruling it out. Every
     * implementation opened with `toList()` anyway, and what a line hands over (`GLine.body`) is a
     * `List` to begin with, so the sequence only ever bought two copies per decode.
     */
    fun decodeParams(tokens: List<GToken>): T

    /**
     * The command [tokens] spell, head included, or null when the head is not this decoder's.
     *
     * What a command word is, is `headWord`'s rule rather than a second copy here, so a head this
     * accepts is one a line can actually carry. Matching is on the head's **number as written** -
     * the lexeme is part of a number's identity in this model, so a non-canonical `M0105` does not
     * resolve - but not on its spacing (`G 1` and `G1` are one head) and not on its case
     * (spec 2.2: `m104` is `M104`, which is `headKey`'s job).
     */
    fun decode(tokens: List<GToken>): T? {
        val head = headWord(tokens) ?: return null
        if (headKey(head()) != headKey(head)) return null
        // `subList`, not `drop`: a view onto the tokens already in hand, the same way a framed
        // line's body is a slice of its raw. Nothing is copied to strip a head.
        return decodeParams(tokens.subList(headEnd(tokens), tokens.size))
    }
}
