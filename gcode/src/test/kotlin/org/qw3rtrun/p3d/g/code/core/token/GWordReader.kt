package org.qw3rtrun.p3d.g.code.core.token

/**
 * The **command-agnostic** reading of a line: every field into a [GWord], the words grouped into
 * [GCommand]s. Test apparatus, and deliberately not production code.
 *
 * Production reads a line with `MarlinCommands.decode` (or one command class's `decode`), which is
 * the stronger reader for one reason: it knows the command number, so it can tell that
 * `M117 Hello World` carries one rest-of-line argument (spec 3.4a) rather than ten fields, and it
 * hands back a typed command with named properties instead of a list of letters. This cannot know
 * that and never will - it is handed tokens with no idea which command lexed them, which is why
 * `marlin.decomposition.txt` still records `M815 G0 X0 Y0|G0 Z10|M300 S440 P50` as four commands.
 * It was `GCommandParser`, it had no production caller left, and it is here rather than deleted
 * because three contracts are measured with it and none of them can be measured with a decoder:
 *
 * - `GDslCorpusTest` asks whether the DSL can re-write **any** corpus line byte for byte. A decoder
 *   answers only for the 295 commands Marlin documents, and normalises what it reads (`X+5` comes
 *   back `X5`), so the 303/279/24 contract cannot be expressed through one.
 * - `GCorpusDecompositionTest` snapshots how every line of two corpora decomposes, including lines
 *   no command class claims.
 * - `MarlinDocExamplesTest` is the *independent* coverage check: it finds parameter letters that no
 *   command class models. Read through the decoders it would only ever see letters the decoders
 *   already know, and would assert nothing.
 *
 * Stateless, so `object` rather than a class to instantiate per test.
 */
object GWordReader {

    /**
     * The commands of [line], reading its body in wire order.
     *
     * The *body*, not the whole line: for a framed line spec section 5 puts the `*` field last, so
     * what follows the marker is outside the frame - `N1 G28*18 G1 X5` is one command.
     */
    fun parse(line: GLine): List<GCommand> = parse(words(line.body))

    /**
     * The fields of [tokens], in wire order (spec sections 2.1 and 3).
     *
     * An identifier followed by a value is one [GParameterWord] carrying both and any whitespace
     * between them, since spec 2.1 lets a space separate a field from its value: `X10`, `X 10` and
     * `X  10` are one word each. An identifier followed by anything else is a [GFlagWord] (spec
     * 3.2), and what followed it is examined again from scratch - `G28 X Y` is three words, not one
     * word and a lost `Y`.
     *
     * Everything that is not a field - separators, comments, the terminator, an unknown character -
     * is simply dropped. It is already in `GLine.raw`, which is what reproduces the input.
     */
    fun words(tokens: List<GToken>): List<GWord> {
        val words = ArrayList<GWord>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token !is GIdentifier) {
                i++
                continue
            }

            val j = valueIndex(tokens, i)
            if (j >= 0) {
                // The word's raw is a slice of the line, so it spells what the line spelled without
                // copying anything: identifier, the spaces it crossed, and the value. The cast is
                // `valueIndex`'s guarantee: it returns an index only for a `GValue`.
                words.add(GParameterWord(token, tokens[j] as GValue, tokens.subList(i, j + 1)))
                i = j + 1
            } else {
                // i + 1, and not past the whitespace the scan crossed: neither it nor the token
                // that ended the field has been consumed, and the latter may start the next word.
                words.add(GFlagWord(token))
                i++
            }
        }
        return words
    }

    fun parse(words: List<GWord>): List<GCommand> {
        val commands = ArrayList<GCommand>()
        var head: GParameterWord<GNumber>? = null
        var params = ArrayList<GWord>()

        for (i in words.indices) {
            val word = words[i]
            if (isStructural(word, i == 0)) continue
            // `head == null` rather than `i == 0` is what makes `T` a command only until one has
            // started: it lets `N1 T0` (an unpaired line number keeps its word) still find `T0`,
            // while keeping the `T` of `G29 T` a parameter.
            val next = commandHead(word, head == null)
            if (next == null) {
                params.add(word)
                continue
            }
            if (head != null) {
                commands.add(GCommand(head, params))
                params = ArrayList()
            }
            // else: whatever came before the first command word stays in `params`. spec 4.3 makes
            // parameter order insignificant, so a leading run belongs to the command it precedes;
            // dropping it here is how a flag goes missing without any test noticing.
            head = next
        }

        if (head != null) commands.add(GCommand(head, params))
        return commands
    }

    /**
     * spec 4: `N` (spec 7) and `*` (spec 8) are structural rather than parametric, so neither is a
     * command or a parameter of one.
     *
     * They are outside a `GPacketLine`'s body, which is why this looked unnecessary at first -
     * but a line that is *not* a well-formed packet keeps them. `N100 M110` is a `GMissingChecksum`
     * whose words are `[N100, M110]`, and without this the line number became a parameter of `M110`,
     * which also re-emitted the line as `M110N100`.
     *
     * **Only the first word can be the line number**, which is why [first] exists. An `N` anywhere
     * else is an ordinary parameter, and exactly one command needs it to be: `M110 N<n>` sets the
     * line-number counter (spec 7.2), and its argument is an `N` word in second position. Skipping
     * every `N` dropped that argument, so `M110 N7` assembled as a bare `M110` and the one command
     * that exists to carry a line number could not be read - found while building
     * [05](https://github.com/qw3rtrun/p3d-web-ui/issues/7).
     *
     * The narrower rule is also the one the firmware uses: Marlin takes a line number only when `N`
     * is the first character of the line (`queue.cpp`: `npos = (*command == 'N') ? command : nullptr`),
     * and then looks for a *second* `N` precisely because `M110`'s argument is one.
     */
    private fun isStructural(word: GWord, first: Boolean): Boolean {
        val id = word.id
        if (id == GChecksum) return true
        if (!first) return false
        return id is GLetter && (id.letter == 'N' || id.letter == 'n')
    }

    /**
     * [word] as a command word, or null when it is not one. [first] is true while no command has
     * started yet, which is the only position a `T` may head one.
     */
    private fun commandHead(word: GWord, first: Boolean): GParameterWord<GNumber>? {
        // spec 4.3: only A-Z are identifiers. `*` is a GChecksum, structural, never a command.
        val id = word.id
        if (id !is GLetter) return null
        if (!isCommandLetter(id.letter, first)) return null

        // spec 4.1: a command word is the letter *followed by a number*. A letter alone is a flag
        // (spec 3.2), so a bare `G` heads nothing.
        if (word !is GParameterWord<*>) return null
        val value = word.value
        if (value !is GNumber) return null
        if (!isCommandNumber(value.lexeme)) return null

        // Rebuilt rather than cast so the type is honest, and carrying `word.raw` keeps the original
        // bytes - including any whitespace the word absorbed, as in `G 1`.
        return GParameterWord(id, value, word.raw)
    }
}
