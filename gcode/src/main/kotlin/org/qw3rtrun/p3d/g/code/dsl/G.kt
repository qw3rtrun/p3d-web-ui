package org.qw3rtrun.p3d.g.code.dsl

import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.token.*
import java.math.BigDecimal
import java.util.function.Consumer

/**
 * The command-building DSL: a facade over the model in `code/core`, for writing G-code rather than
 * reading it.
 *
 * The rule it is built to satisfy is **any valid G-code can be written with it**, and
 * `GDslCorpusTest` keeps that honest by re-writing a real corpus line by line and comparing bytes.
 * Concretely that means it has to reach past commands and parameters to the things a naive builder
 * forgets: comments (41% of the corpus's non-blank lines carry one), flags, subcodes, non-canonical
 * lexemes like `G01` and `X10.50`, lowercase letters, strings, expressions, and more than one
 * command on a line.
 *
 * ```
 * G(1, X("10.5"), F(1800)) comment " move"     // G1 X10.5 F1800 ; move
 * G(28, X, Y)                                   // G28 X Y
 * G("29.1", P(1))                               // G29.1 P1
 * M(117, S(text("Hello")))                      // M117 S"Hello"
 * block(G(53), G(0, X(0)))                      // G53 G0 X0
 * commentLine(" LAYER:42")                      // ; LAYER:42
 * ```
 *
 * **Builders return values.** Nothing here emits, so a command can be built, inspected and asserted
 * on before anything is sent; [GSender] is a thin sink layered on top for callers that want one.
 * The facade this replaces took its sink in the constructor and returned `Unit`, which is why its
 * own test had to keep a mutable list to see what it had produced.
 *
 * **Unlike `code/core`, this file is allowed full Kotlin** - extension and infix functions, default
 * arguments, overloads. The layering rule makes only the `code/core` package portable; this is a
 * host-side convenience over it, and a port re-implements the core and writes its own facade.
 *
 * Parameter words live in `GWords.kt`.
 */

// ---------------------------------------------------------------------------
// Commands - spec 4
// ---------------------------------------------------------------------------

/** `G<number>` with [params], as in `G(1, X(10), F(1800))`. */
fun G(number: Int, vararg params: GWord): GCommand = command('G', number, *params)

/** `G<lexeme>`, for a subcode or a non-canonical number: `G("29.1")`, `G("01")`. */
fun G(lexeme: String, vararg params: GWord): GCommand = command('G', lexeme, *params)

/** `M<number>` with [params]. */
fun M(number: Int, vararg params: GWord): GCommand = command('M', number, *params)

/** `M<lexeme>`, for a subcode or a non-canonical number. */
fun M(lexeme: String, vararg params: GWord): GCommand = command('M', lexeme, *params)

/** `T<number>` - tool select, spec 4.1. */
fun T(number: Int, vararg params: GWord): GCommand = command('T', number, *params)

/** `T<lexeme>`. */
fun T(lexeme: String, vararg params: GWord): GCommand = command('T', lexeme, *params)

/**
 * `<letter><number>` for any command letter, including the ones spec 4.1 leaves to a dialect.
 *
 * **Where a letter is both a command and a parameter, one spelling has to win**, because a Kotlin
 * name cannot return two types. Two letters are affected, and each is resolved the way the parser
 * resolves it, so that builder and parser agree:
 *
 * - **`T`** heads a command (`T(0)` is `T0`, spec 4.1) - the reading `GCommandParser` takes while no
 *   command has started. As a *parameter* - `M105 T1`, `G29 T`, 40 corpus lines - write
 *   `word('T', 1)` or `flag('T')`.
 * - **`D`** is a parameter (`D(3)` is `D3`, spec 4.2's diameter and PID `D`) - the reading the
 *   parser takes, because it is the common one. As the Marlin debug *command* spec 4.1 lists, write
 *   `command('D', 3)`; the parser will still read it back as a parameter, and that asymmetry is the
 *   spec's own ambiguity rather than this file's.
 */
fun command(letter: Char, number: Int, vararg params: GWord): GCommand =
    command(letter, number.toString(), *params)

/** `<letter><lexeme>` for any command letter. */
fun command(letter: Char, lexeme: String, vararg params: GWord): GCommand {
    // spec 4.1, using the parser's own rule so the DSL cannot build a command that will not read
    // back. `G-1` and `G29.` are not commands, and finding that out at the call site beats
    // discovering it when a printer answers `echo:Unknown command`.
    require(GCommandParser.isCommandNumber(lexeme)) {
        "spec 4.1: a command number is an unsigned integer with an optional subcode, got '$lexeme'"
    }
    for (param in params) {
        // spec 8: `*` is never a parameter - it is the line's checksum field, and `GEncoder.frame`
        // is what puts one there. A block carrying its own `*` would be checksummed twice.
        //
        // `N` is deliberately *not* refused, even though it is structural too. Spec 7.1 makes only
        // the **first field of a line** a line number, so an `N` inside a command is an ordinary
        // parameter - and one command's argument is exactly that: `M110 N7` sets the line-number
        // counter (spec 7.2). Refusing it here would have made `M110` unwritable, which is the same
        // over-wide rule todo 05 had to narrow in `GCommandParser.isStructural`.
        require(param.id != GChecksum) {
            "spec 8: `*` is the line's checksum field, not a parameter - use GEncoder.frame"
        }
    }
    return GCommand(identifier(letter), number(lexeme), params.toList())
}

// ---------------------------------------------------------------------------
// Lines - spec 5
// ---------------------------------------------------------------------------

/** A line made of [parts] in order: commands and comments, as spec 5 allows. */
fun block(vararg parts: GBlockPart): GBlock = GBlock(parts.toList())

/** This command as a line of its own - the common case. */
fun GCommand.line(): GBlock = GBlock(listOf(this))

/** This command with a trailing `;` comment: `G(1, X(10)) comment " move"`. */
infix fun GCommand.comment(text: String): GBlock = GBlock(listOf(this, tailComment(text)))

/** This line with a trailing `;` comment appended. */
infix fun GBlock.comment(text: String): GBlock = GBlock(parts + tailComment(text))

/** A line that is nothing but a `;` comment - spec 5 calls it a no-op. */
fun commentLine(text: String): GBlock = GBlock(listOf(tailComment(text)))

// ---------------------------------------------------------------------------
// The sink
// ---------------------------------------------------------------------------

/**
 * Renders what the builders produce and hands the text to [out].
 *
 * Deliberately thin, and deliberately separate from the builders: it holds no line number and no
 * window, because that is `GCodeReader`/`GSendWindow`'s job (todo 05) and duplicating it here would
 * give a caller two counters that could disagree. Send framed lines by passing
 * `GEncoder.frame(...)` output to a window instead.
 *
 * The named operations below exist to carry over the Java facade this replaces, so its callers can
 * move across unchanged. They are a convenience layer with no privileges - each is one line of DSL.
 */
class GSender(private val out: (String) -> Unit) {

    /**
     * For Java callers, who cannot pass a method reference where a Kotlin `(String) -> Unit` is
     * expected - `void` is not `Unit`, so `new GSender(this::onG)` would not compile against the
     * primary constructor. `java.util.function.Consumer` is a JVM type and would not be allowed in
     * `code/core`; here it is, and it is what lets the Java facade this replaces be swapped out
     * without touching its callers' shape.
     */
    constructor(out: Consumer<String>) : this({ text -> out.accept(text) })

    fun send(block: GBlock) = out(GEncoder.encode(block))

    fun send(command: GCommand) = send(command.line())

    /** `M105` - report hotend temperature, optionally for one tool. */
    fun m105(index: Int? = null) =
        send(if (index == null) M(105) else M(105, word('T', index)))

    /** `M115` - firmware info. */
    fun m115() = send(M(115))

    /** `M155` - auto-report temperature, optionally every [period] seconds. */
    fun m155(period: Int? = null) =
        send(if (period == null) M(155) else M(155, S(period)))

    /** `M140` - set bed temperature. */
    fun m140(temp: BigDecimal) = send(M(140, S(temp)))

    fun tempReport(tool: Int? = null) = m105(tool)

    fun autoReportTemp(period: Int? = null) = m155(period)

    fun firmwareInfo() = m115()

    fun setBedTemperature(temp: BigDecimal) = m140(temp)
}
