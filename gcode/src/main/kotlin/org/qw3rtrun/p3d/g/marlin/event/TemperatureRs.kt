package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.core.msg.TemperatureReport
import org.qw3rtrun.p3d.core.msg.TemperatureReportedEvent
import org.qw3rtrun.p3d.g.protocol.GEventRs
import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.OkRs
import java.math.BigDecimal
import java.util.regex.Pattern

/**
 * One sensor's line in a temperature report: where it is, where it is going, how hard it is driven.
 *
 * [target] is absent for a sensor that cannot be heated - the probe and the board report a reading
 * and nothing else. [power] is absent for every sensor Marlin does not write a `@` field for, which
 * is all of them except the active hotend, the bed, the chamber and the individual hotends.
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
 * slot the printer never granted, and run ahead of the queue. The payload is identical, so it is
 * declared once here and the two implementations differ only in what they answer to.
 *
 * Every sensor is optional and absent by default, because which ones exist is a compile-time
 * decision in the firmware: a printer with no heated bed sends no `B` field at all. The decoder
 * this replaces required both `T` and `B` and so read nothing at all from such a machine.
 *
 * Marlin's letters, from `Temperature::print_heater_state`: `T` hotend, `B` bed, `C` chamber,
 * `P` probe, `L` cooler, `M` board, `R` redundant, and `T0`..`T7` for the individual hotends of a
 * multi-hotend machine, which are reported *in addition to* the active one in `T`.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
interface TemperatureRs<D : TemperatureRs<D>> : GEventRs<D>, TemperatureReportedEvent {

    val hotend: HeaterReading?
    val bed: HeaterReading?
    val chamber: HeaterReading?
    val probe: HeaterReading?
    val cooler: HeaterReading?
    val board: HeaterReading?
    val redundant: HeaterReading?

    /** The per-hotend readings of a multi-hotend machine, keyed by tool index. */
    val hotends: Map<Int, HeaterReading>

    /**
     * True when the report rode in on an `ok`, which is how `M109` and `M190` end their wait.
     *
     * Derived from the type rather than stored, so it cannot disagree with what the value answers
     * to: it is exactly `this is OkRs<*>`.
     */
    val ok: Boolean get() = this is OkRs<*>

    override fun hotend(): TemperatureReport? = hotend?.let(TemperatureFields::toReport)

    override fun bed(): TemperatureReport? = bed?.let(TemperatureFields::toReport)

    override fun encode(): String = TemperatureFields.encode(this)
}

/** A temperature report on a line of its own: `M105`'s answer, or `M155`'s auto-report. */
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

        override fun match(line: String): Boolean {
            val scanned = TemperatureFields.scan(line)
            return scanned != null && !scanned.ok
        }

        override fun decodeParams(line: String): BareTemperatureRs {
            val scanned = TemperatureFields.scan(line)
            require(scanned != null && !scanned.ok) { "not a bare temperature report: $line" }
            return scanned.toBare()
        }
    }
}

/**
 * A temperature report carried on an `ok`, which is how `M109` and `M190` end their wait.
 *
 * It answers to [OkRs], so a host that acknowledges on `OKReceivedEvent` sees this line for what it
 * is instead of treating it as an unrelated report and stalling on the `ok` it is still waiting for.
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

        override fun match(line: String): Boolean {
            val scanned = TemperatureFields.scan(line)
            return scanned != null && scanned.ok
        }

        override fun decodeParams(line: String): OkTemperatureRs {
            val scanned = TemperatureFields.scan(line)
            require(scanned != null && scanned.ok) { "not an ok temperature report: $line" }
            return scanned.toOk()
        }
    }
}

/** A temperature report of either kind. */
object TemperatureRsDecoder : GRsDecoder<TemperatureRs<*>> {

    override fun match(line: String): Boolean = TemperatureFields.scan(line) != null

    override fun decodeParams(line: String): TemperatureRs<*> {
        val scanned = TemperatureFields.scan(line)
        require(scanned != null) { "not a temperature report: $line" }
        return if (scanned.ok) scanned.toOk() else scanned.toBare()
    }
}

/**
 * The wire format, shared by both classes: one scanner in, one writer out.
 *
 * Kept here rather than duplicated across the two so that a change to Marlin's field set is made
 * once. [Fields] is what the scanner produces before it is known which class the line becomes.
 */
internal object TemperatureFields {

    // A power field (`@:127`, `B@:80`, `@1:0`) or a sensor field (`T:210.00 /210.00`). Power comes
    // first in the alternation because `B@` would otherwise start matching as sensor `B`.
    private val FIELD = Pattern.compile(
        "(?>([BC]?)@([0-9]?):[ \t]*([0-9]+))" +
                "|(?>([TBCPLMR])([0-9]?):[ \t]*([-+]?(?>[0-9]*\\.)?[0-9]+)" +
                "(?>[ \t]*/[ \t]*([-+]?(?>[0-9]*\\.)?[0-9]+))?)",
        Pattern.CASE_INSENSITIVE
    )

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

    fun toReport(reading: HeaterReading): TemperatureReport =
        TemperatureReport(
            reading.current.toDouble(),
            reading.target?.toDouble() ?: 0.0,
            reading.power ?: 0,
        )

    fun encode(rs: TemperatureRs<*>): String {
        val out = StringBuilder()
        if (rs.ok) out.append("ok")
        // Marlin's own field order, from print_heater_states: the sensors first, then every power
        // field. A host that re-encodes a report has to produce the order a printer would.
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
        return out.toString().trim()
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
     * that the rest of the line was dropped on the floor.
     */
    fun scan(line: String): Fields? {
        var rest = line.trim()
        val ok = rest.startsWith("ok", ignoreCase = true) &&
                (rest.length == 2 || !rest[2].isLetterOrDigit())
        if (ok) rest = rest.substring(2)

        val temps = HashMap<String, Pair<BigDecimal, BigDecimal?>>()
        val powers = HashMap<String, Int>()
        var at = 0
        val matcher = FIELD.matcher(rest)
        while (at < rest.length) {
            if (rest[at] == ' ' || rest[at] == '\t') {
                at++
                continue
            }
            if (!matcher.find(at) || matcher.start() != at) return null
            if (matcher.group(4) != null) {
                val key = matcher.group(4).uppercase() + matcher.group(5)
                if (temps.put(key, readTemp(matcher) ?: return null) != null) return null
            } else {
                val key = matcher.group(1).uppercase() + "@" + matcher.group(2)
                // spec 9: a digit run too wide for Int is malformed input off the wire.
                val power = matcher.group(3).toIntOrNull() ?: return null
                if (powers.put(key, power) != null) return null
            }
            at = matcher.end()
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
            // `C@` is written for the chamber and for the cooler alike - a Marlin quirk, not a
            // choice this code can make. It goes to the chamber, the commoner of the two.
            chamber = temps["C"]?.let { reading(it, powers["C@"]) },
            probe = temps["P"]?.let { reading(it, null) },
            cooler = temps["L"]?.let { reading(it, null) },
            board = temps["M"]?.let { reading(it, null) },
            redundant = temps["R"]?.let { reading(it, null) },
            hotends = hotends,
        )
    }

    private fun readTemp(matcher: java.util.regex.Matcher): Pair<BigDecimal, BigDecimal?>? {
        val current = matcher.group(6).toBigDecimalOrNull() ?: return null
        val target = matcher.group(7)?.let { it.toBigDecimalOrNull() ?: return null }
        return current to target
    }

    private fun reading(value: Pair<BigDecimal, BigDecimal?>, power: Int?): HeaterReading =
        HeaterReading(value.first, value.second, power)
}
