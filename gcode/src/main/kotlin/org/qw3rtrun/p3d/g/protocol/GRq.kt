package org.qw3rtrun.p3d.g.protocol

import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GCommandParser
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GToken

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
     * The sequence is read more than once - one pass per parameter - so a caller passing a
     * single-use sequence gets an exception from the sequence, not a half-read command.
     */
    fun decodeParams(tokens: Sequence<GToken>): T

    /**
     * The command [tokens] spell, head included, or null when the head is not this decoder's.
     *
     * What a command word is, is `GCommandParser`'s rule rather than a second copy here, so a head
     * this accepts is one a line can actually carry. Matching is on the head as written - the
     * lexeme is part of a number's identity in this model, so a non-canonical `M0105` does not
     * resolve - but not on its spacing: `G 1` and `G1` are the same head.
     */
    fun decode(tokens: Sequence<GToken>): T? {
        val all = tokens.toList()
        val head = GCommandParser.headWord(all) ?: return null
        if (head() != head) return null
        return decodeParams(all.asSequence().drop(GCommandParser.headEnd(all)))
    }
}
