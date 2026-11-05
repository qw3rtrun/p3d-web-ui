package org.qw3rtrun.p3d.g.marlin.event

import java.math.BigDecimal
import org.qw3rtrun.p3d.g.protocol.GEventRs
import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.OkRs
import org.qw3rtrun.p3d.g.code.core.token.asciiFold
import org.qw3rtrun.p3d.g.protocol.asciiTrim
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.digitsEnd
import org.qw3rtrun.p3d.g.protocol.numberEnd
import org.qw3rtrun.p3d.g.protocol.startsWithAscii

/**
 * One sensor's line in a temperature report: where it is, where it is going, how hard it is driven.
 *
 * [target] is null for a sensor that cannot be heated - the probe and the board report a reading and
 * nothing else. [power] is null for every sensor Marlin does not write a `@` field for, which is all
 * of them except the active hotend, the bed, the chamber and the individual hotends.
 *
 * ```
 * BareTemperatureRs.decode("T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0")?.bed
 * // HeaterReading(current = 23.87, target = 0.00, power = 0)
 * ```
 */
data class HeaterReading(
    val current: BigDecimal,
    val target: BigDecimal? = null,
    val power: Int? = null,
)

/**
 * The temperature report - `M105`'s answer, `M155`'s auto-report, and the tail of the `ok` that
 * ends an `M109` or `M190` wait.
 *
 * ```
 * ok T:210.00 /210.00 B:60.00 /60.00 @:127 B@:80     OkTemperatureRs
 * T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0               BareTemperatureRs
 * ```
 *
 * **Two classes, because `ok` is a type and not a flag.** A report that arrives on an `ok` *is* an
 * acknowledgement and answers to [OkRs]; a bare `M155` auto-report is not one, and must never be
 * counted as one - a host that frees a slot in its send window per `OKReceivedEvent` would free a
 * slot the printer never granted, and run ahead of the queue. The payload is declared once here, and
 * the two implementations differ only in what they answer to.
 *
 * Every sensor ([hotend] `T`, [bed] `B`, [chamber] `C`, [probe] `P`, [cooler] `L`, [board] `M`,
 * [redundant] `R`, letters from `Temperature::print_heater_state`) is optional and absent by default,
 * because which ones exist is a compile-time decision in the firmware: a printer with no heated bed
 * sends no `B` field at all - the decoder this replaced required both `T` and `B` and so read nothing
 * from such a machine. [hotends] carries the per-tool readings of a multi-hotend machine, `T0`..`T7`,
 * reported *in addition to* the active one in [hotend].
 *
 * [ok] is true exactly when the report rode in on an `ok` - derived from the type (`this is OkRs<*>`)
 * rather than stored, so it cannot disagree with what the value answers to.
 *
 * ```
 * TemperatureRsDecoder.decode("ok T:210.00 /210.00")?.ok   // true
 * TemperatureRsDecoder.decode("T:24.31 /0.00")?.bed        // null - no heated bed reported
 * ```
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
interface TemperatureRs<D : TemperatureRs<D>> : GEventRs<D> {

    val hotend: HeaterReading?
    val bed: HeaterReading?
    val chamber: HeaterReading?
    val probe: HeaterReading?
    val cooler: HeaterReading?
    val board: HeaterReading?
    val redundant: HeaterReading?

    val hotends: Map<Int, HeaterReading>

    val ok: Boolean get() = this is OkRs<*>

    override fun encode(): String = TemperatureFields.encode(this)
}

/**
 * A temperature report on a line of its own: `M105`'s answer, or `M155`'s auto-report. It never
 * matches a line that starts with `ok`.
 *
 * ```
 * BareTemperatureRs.decode("T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0")?.encode()
 * // "T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0"
 * BareTemperatureRs.decode("ok T:210.00 /210.00")   // null
 * ```
 */
data class BareTemperatureRs(
    override val hotend: HeaterReading? = null,
    override val bed: HeaterReading? = null,
    override val chamber: HeaterReading? = null,
    override val probe: HeaterReading? = null,
    override val cooler: HeaterReading? = null,
    override val board: HeaterReading? = null,
    override val redundant: HeaterReading? = null,
    override val hotends: Map<Int, HeaterReading> = emptyMap(),
) : TemperatureRs<BareTemperatureRs> {

    override fun toString(): String = "BareTemperatureRs(" + encode() + ")"

    companion object : GRsDecoder<BareTemperatureRs> {

        /** One scan: a report that arrived on an `ok` is [OkTemperatureRs] and not this. */
        override fun decode(line: String): BareTemperatureRs? {
            val scanned = TemperatureFields.scan(line) ?: return null
            if (scanned.ok) return null
            return scanned.toBare()
        }
    }
}

/**
 * A temperature report carried on an `ok`, which is how `M109` and `M190` end their wait.
 *
 * It answers to [OkRs], so a host that acknowledges on `OKReceivedEvent` sees this line for what it
 * is instead of treating it as an unrelated report and stalling on the `ok` it is still waiting for.
 *
 * ```
 * OkTemperatureRs.decode("ok T:210.00 /210.00 B:60.00 /60.00 @:127 B@:80")?.hotend?.power   // 127
 * ```
 */
data class OkTemperatureRs(
    override val hotend: HeaterReading? = null,
    override val bed: HeaterReading? = null,
    override val chamber: HeaterReading? = null,
    override val probe: HeaterReading? = null,
    override val cooler: HeaterReading? = null,
    override val board: HeaterReading? = null,
    override val redundant: HeaterReading? = null,
    override val hotends: Map<Int, HeaterReading> = emptyMap(),
) : TemperatureRs<OkTemperatureRs>, OkRs<OkTemperatureRs> {

    override fun toString(): String = "OkTemperatureRs(" + encode() + ")"

    companion object : GRsDecoder<OkTemperatureRs> {

        /** One scan: a report on its own line is [BareTemperatureRs] and not this. */
        override fun decode(line: String): OkTemperatureRs? {
            val scanned = TemperatureFields.scan(line) ?: return null
            if (!scanned.ok) return null
            return scanned.toOk()
        }
    }
}

/**
 * A temperature report of either kind.
 *
 * ```
 * TemperatureRsDecoder.decode("T:24.31 /0.00")               // BareTemperatureRs
 * TemperatureRsDecoder.decode("//action:prompt_begin T:1")   // null
 * ```
 */
object TemperatureRsDecoder : GRsDecoder<TemperatureRs<*>> {

    /** One scan for either kind - the `ok` prefix decides which class it becomes. */
    override fun decode(line: String): TemperatureRs<*>? {
        val scanned = TemperatureFields.scan(line) ?: return null
        return if (scanned.ok) scanned.toOk() else scanned.toBare()
    }
}

/**
 * The wire format, shared by both classes: one scanner in, one writer out.
 *
 * Kept here rather than duplicated across the two so that a change to Marlin's field set is made
 * once. [Fields] is what the scanner produces before it is known which class the line becomes.
 *
 * A field is either a power field (`@:127`, `B@:80`, `@1:0`) or a sensor field (`T:210.00 /210.00`).
 * Power comes first in the scanner's alternation because `B@` would otherwise start matching as
 * sensor `B`.
 *
 * ```
 * TemperatureFields.scan("ok T:1.0")?.ok   // true
 * ```
 */
internal object TemperatureFields {

    /**
     * One scanned report, before it is known whether it becomes a [BareTemperatureRs] or an
     * [OkTemperatureRs]: the same payload, plus whether the line opened with `ok`.
     *
     * ```
     * TemperatureFields.scan("T:24.31 /0.00")?.toBare()   // BareTemperatureRs(T:24.31 /0.00)
     * ```
     *
     * @property ok whether the line opened with `ok`
     */
    class Fields(
        val ok: Boolean,
        val hotend: HeaterReading?,
        val bed: HeaterReading?,
        val chamber: HeaterReading?,
        val probe: HeaterReading?,
        val cooler: HeaterReading?,
        val board: HeaterReading?,
        val redundant: HeaterReading?,
        val hotends: Map<Int, HeaterReading>,
    ) {
        fun toBare() = BareTemperatureRs(hotend, bed, chamber, probe, cooler, board, redundant, hotends)

        fun toOk() = OkTemperatureRs(hotend, bed, chamber, probe, cooler, board, redundant, hotends)
    }

    /**
     * [rs] as the line a printer would send. The fields go in Marlin's own order, from
     * `print_heater_states`: the sensors first, then every power field. A host that re-encodes a
     * report has to produce the order a printer would.
     *
     * ```
     * TemperatureFields.encode(BareTemperatureRs(hotend = HeaterReading(BigDecimal("1.0"))))   // "T:1.0"
     * ```
     */
    fun encode(rs: TemperatureRs<*>): String {
        val out = StringBuilder()
        if (rs.ok) out.append("ok")
        appendTemp(out, 'T', rs.hotend)
        appendTemp(out, 'B', rs.bed)
        appendTemp(out, 'C', rs.chamber)
        appendTemp(out, 'L', rs.cooler)
        appendTemp(out, 'P', rs.probe)
        appendTemp(out, 'M', rs.board)
        appendTemp(out, 'R', rs.redundant)
        for ((index, reading) in rs.hotends.entries.sortedBy { it.key }) {
            appendTemp(out, 'T', reading, index)
        }
        appendPower(out, "@", rs.hotend?.power)
        appendPower(out, "B@", rs.bed?.power)
        appendPower(out, "C@", rs.chamber?.power)
        for ((index, reading) in rs.hotends.entries.sortedBy { it.key }) {
            appendPower(out, "@$index", reading.power)
        }
        return out.toString().asciiTrim()
    }

    private fun appendTemp(out: StringBuilder, letter: Char, reading: HeaterReading?, index: Int? = null) {
        if (reading == null) return
        out.append(' ').append(letter)
        if (index != null) out.append(index)
        out.append(':').append(reading.current.toPlainString())
        if (reading.target != null) out.append(" /").append(reading.target.toPlainString())
    }

    private fun appendPower(out: StringBuilder, label: String, power: Int?) {
        if (power == null) return
        out.append(' ').append(label).append(':').append(power)
    }

    /**
     * The whole line or nothing.
     *
     * Every field has to be one this knows, with only whitespace between them, and there has to be
     * at least one sensor. A looser scan that took any line *containing* a `T:` would claim
     * `//action:prompt_begin T:1` and half the `echo:` traffic, and the caller would never learn
     * that the rest of the line was dropped on the floor. Spec 9: a power value too wide for `Int` is
     * malformed input off the wire, so the line does not scan.
     *
     * `C@` is written for the chamber and for the cooler alike - a Marlin quirk, not a choice this
     * code can make. It goes to the chamber, the commoner of the two.
     *
     * ```
     * TemperatureFields.scan("ok T:1.0")?.ok                     // true
     * TemperatureFields.scan("//action:prompt_begin T:1")        // null
     * ```
     */
    fun scan(line: String): Fields? {
        var rest = line.asciiTrim()
        val ok = rest.startsWithAscii("ok") &&
                (rest.length == 2 || !isAlphanumeric(rest[2]))
        if (ok) rest = rest.substring(2)

        val temps = HashMap<String, Pair<BigDecimal, BigDecimal?>>()
        val powers = HashMap<String, Int>()
        var at = 0
        while (at < rest.length) {
            if (rest[at] == ' ' || rest[at] == '\t') {
                at++
                continue
            }

            // A power field first: `B@` would otherwise start reading as sensor `B`, which is the
            // order the alternation this replaced had for the same reason.
            val marker = powerMarker(rest, at)
            at = if (marker < 0) readSensor(rest, at, temps) else readPower(rest, at, marker, powers)
            if (at < 0) return null
        }
        if (temps.isEmpty()) return null

        val hotends = HashMap<Int, HeaterReading>()
        for ((key, value) in temps) {
            if (key.length == 2 && key[0] == 'T') {
                hotends[key[1] - '0'] = reading(value, powers["@" + key[1]])
            }
        }
        return Fields(
            ok = ok,
            hotend = temps["T"]?.let { reading(it, powers["@"]) },
            bed = temps["B"]?.let { reading(it, powers["B@"]) },
            chamber = temps["C"]?.let { reading(it, powers["C@"]) },
            probe = temps["P"]?.let { reading(it, null) },
            cooler = temps["L"]?.let { reading(it, null) },
            board = temps["M"]?.let { reading(it, null) },
            redundant = temps["R"]?.let { reading(it, null) },
            hotends = hotends,
        )
    }

    /**
     * Where the `@` of a power field at [at] sits, or -1 when none starts there. `@:127` is the
     * active hotend's, `B@:80`/`C@:30` name a sensor, `@1:0` names a hotend by index - the letter in
     * front of `@` is what tells `B@:80` from the sensor field `B:60`.
     */
    private fun powerMarker(text: String, at: Int): Int {
        if (text[at] == '@') return at
        if (!isChar(text[at], 'B') && !isChar(text[at], 'C')) return -1
        return if (at + 1 < text.length && text[at + 1] == '@') at + 1 else -1
    }

    /**
     * Reads `[B|C]@[index]:<digits>` into [powers], answering the index past it, or -1 when none is
     * there. The value is unsigned and whole, a PWM duty; spec 9 makes a run too wide for an `Int`
     * malformed input off the wire, so the line does not scan rather than the decoder throwing.
     */
    private fun readPower(text: String, at: Int, marker: Int, powers: HashMap<String, Int>): Int {
        val prefix = if (marker == at) "" else asciiFold(text[at]).toString()

        var i = marker + 1
        var index = ""
        if (i < text.length && isDigit(text[i])) {
            index = text[i].toString()
            i++
        }

        if (i >= text.length || text[i] != ':') return -1
        i = text.blanksEnd(i + 1)
        val end = text.digitsEnd(i)
        if (end == i) return -1

        val power = text.substring(i, end).toIntOrNull() ?: return -1
        if (powers.put(prefix + "@" + index, power) != null) return -1
        return end
    }

    /**
     * Reads `<letter>[index]:<current>[ / <target>]` into [temps], answering the index past it, or
     * -1 when none is there. The target is optional because the RepRap spec's own example omits it
     * (`T:93.2 B:22.9`), and absent stays absent rather than becoming zero.
     */
    private fun readSensor(
        text: String,
        at: Int,
        temps: HashMap<String, Pair<BigDecimal, BigDecimal?>>,
    ): Int {
        if (!isSensor(text[at])) return -1
        val letter = asciiFold(text[at])

        var i = at + 1
        var index = ""
        if (i < text.length && isDigit(text[i])) {
            index = text[i].toString()
            i++
        }

        if (i >= text.length || text[i] != ':') return -1
        i = text.blanksEnd(i + 1)
        var end = text.numberEnd(i)
        if (end < 0) return -1
        val current = text.substring(i, end).toBigDecimalOrNull() ?: return -1

        var target: BigDecimal? = null
        val slash = text.blanksEnd(end)
        if (slash < text.length && text[slash] == '/') {
            val from = text.blanksEnd(slash + 1)
            val targetEnd = text.numberEnd(from)
            if (targetEnd < 0) return -1
            target = text.substring(from, targetEnd).toBigDecimalOrNull() ?: return -1
            end = targetEnd
        }

        if (temps.put(letter + index, current to target) != null) return -1
        return end
    }

    /** `print_heater_states`' letters: hotend, bed, chamber, probe, cooLer, Motherboard, Redundant. */
    private fun isSensor(c: Char): Boolean {
        val f = asciiFold(c)
        return f == 'T' || f == 'B' || f == 'C' || f == 'P' || f == 'L' || f == 'M' || f == 'R'
    }

    private fun isChar(c: Char, upper: Char) = asciiFold(c) == upper

    private fun isDigit(c: Char) = c >= '0' && c <= '9'

    /**
     * Explicit ASCII, because `Char.isLetterOrDigit()` - which this replaced - accepts the whole
     * Unicode category, and the wire is 7-bit (spec 1.1). The outcome for a non-ASCII character
     * after `ok` is unchanged either way: the scan then fails on a field it cannot read.
     */
    private fun isAlphanumeric(c: Char) =
        isDigit(c) || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')

    private fun reading(value: Pair<BigDecimal, BigDecimal?>, power: Int?): HeaterReading =
        HeaterReading(value.first, value.second, power)
}
