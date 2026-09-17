package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.core.msg.TemperatureReport
import org.qw3rtrun.p3d.core.msg.TemperatureReportedEvent
import org.qw3rtrun.p3d.g.code.dsl.GRsDecoder
import org.qw3rtrun.p3d.g.code.dsl.GRs
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
 * ok T:210.00 /210.00 B:60.00 /60.00 @:127 B@:80
 * T:24.31 /0.00 B:23.87 /0.00 T0:24.31 /0.00 T1:25.02 /0.00 @:0 B@:0 @0:0 @1:0
 * ```
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
data class TemperatureRs(
    val hotend: HeaterReading? = null,
    val bed: HeaterReading? = null,
    val chamber: HeaterReading? = null,
    val probe: HeaterReading? = null,
    val cooler: HeaterReading? = null,
    val board: HeaterReading? = null,
    val redundant: HeaterReading? = null,
    /** The per-hotend readings of a multi-hotend machine, keyed by tool index. */
    val hotends: Map<Int, HeaterReading> = emptyMap(),
    /** True when the report rode in on an `ok`, which is how `M109` and `M190` end their wait. */
    val ok: Boolean = false,
) : GRs<TemperatureRs>, TemperatureReportedEvent {

    override fun hotend(): TemperatureReport? = hotend?.toReport()

    override fun bed(): TemperatureReport? = bed?.toReport()

    override fun encode(): String {
        val out = StringBuilder()
        if (ok) out.append("ok")
        // Marlin's own field order, from print_heater_states: the sensors first, then every power
        // field. A host that re-encodes a report has to produce the order a printer would.
        appendTemp(out, 'T', hotend)
        appendTemp(out, 'B', bed)
        appendTemp(out, 'C', chamber)
        appendTemp(out, 'L', cooler)
        appendTemp(out, 'P', probe)
        appendTemp(out, 'M', board)
        appendTemp(out, 'R', redundant)
        for ((index, reading) in hotends.entries.sortedBy { it.key }) {
            appendTemp(out, 'T', reading, index)
        }
        appendPower(out, "@", hotend?.power)
        appendPower(out, "B@", bed?.power)
        appendPower(out, "C@", chamber?.power)
        for ((index, reading) in hotends.entries.sortedBy { it.key }) {
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

    private fun HeaterReading.toReport(): TemperatureReport =
        TemperatureReport(current.toDouble(), target?.toDouble() ?: 0.0, power ?: 0)

    companion object : GRsDecoder<TemperatureRs> {

        // A power field (`@:127`, `B@:80`, `@1:0`) or a sensor field (`T:210.00 /210.00`). Power
        // comes first in the alternation because `B@` would otherwise start matching as sensor `B`.
        private val FIELD = Pattern.compile(
            "(?>([BC]?)@([0-9]?):[ \t]*([0-9]+))" +
                "|(?>([TBCPLMR])([0-9]?):[ \t]*([-+]?(?>[0-9]*\\.)?[0-9]+)" +
                "(?>[ \t]*/[ \t]*([-+]?(?>[0-9]*\\.)?[0-9]+))?)",
            Pattern.CASE_INSENSITIVE
        )

        override fun match(line: String): Boolean = scan(line) != null

        override fun decodeParams(line: String): TemperatureRs {
            val scanned = scan(line)
            require(scanned != null) { "not a temperature report: $line" }
            return scanned
        }

        /**
         * The whole line or nothing.
         *
         * Every field has to be one this knows, with only whitespace between them, and there has to
         * be at least one sensor. A looser scan that took any line *containing* a `T:` would claim
         * `//action:prompt_begin T:1` and half the `echo:` traffic, and the caller would never learn
         * that the rest of the line was dropped on the floor.
         */
        private fun scan(line: String): TemperatureRs? {
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
            return TemperatureRs(
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
                ok = ok,
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
}
