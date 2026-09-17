package org.qw3rtrun.p3d.g.marlin.protocol

import org.qw3rtrun.p3d.core.msg.OKReceivedEvent
import org.qw3rtrun.p3d.g.code.dsl.GRs
import org.qw3rtrun.p3d.g.code.dsl.GRsDecoder
import java.util.regex.Pattern

interface OkRs<D : OkRs<D>> : GRs<D>, OKReceivedEvent

object SimpleOkRs : OkRs<SimpleOkRs>, GRsDecoder<SimpleOkRs> {
    override fun encode(): String = "ok"
    override fun match(line: String): Boolean = line.trim().equals("ok", ignoreCase = true)
    override fun decodeParams(line: String): SimpleOkRs = this
}

data class AdvancedOkRs(
    val planner: Int,
    val blockQueue: Int,
    val lineNumber: Int? = null
) : OkRs<AdvancedOkRs> {

    override fun encode(): String = "ok P$planner B$blockQueue${if (lineNumber != null) " N$lineNumber" else ""}"

    companion object : GRsDecoder<AdvancedOkRs> {
        private val ADVANCED_OK_PATTERN = Pattern.compile(
            "^[oO][kK] +([PpBb])([0-9]+) +([PpBb])([0-9]+)(?> +([Nn])([0-9]+))?"
        )

        override fun match(line: String): Boolean {
            val matcher = ADVANCED_OK_PATTERN.matcher(line.trim())
            if (!matcher.matches()) return false
            if (matcher.group(1).equals(matcher.group(3), ignoreCase = true)) return false
            if (matcher.group(2).toIntOrNull() == null) return false
            if (matcher.group(4).toIntOrNull() == null) return false
            if (matcher.group(5) != null && matcher.group(6).toIntOrNull() == null) return false
            return true
        }

        override fun decodeParams(line: String): AdvancedOkRs {
            val matcher = ADVANCED_OK_PATTERN.matcher(line.trim())
            require(matcher.matches())
            var planner = -1
            var blockQueue = -1
            val first = matcher.group(2).toInt()
            val second = matcher.group(4).toInt()
            when (matcher.group(1)) {
                "P", "p" -> planner = first
                "B", "b" -> blockQueue = first
            }
            when (matcher.group(3)) {
                "B", "b" -> blockQueue = second
                "P", "p" -> planner = second
            }
            // null and not -1: the property is nullable and encode() writes out any non-null
            // line number, so a -1 here re-encoded to `ok P15 B3 N-1` - a line nothing sends and
            // this decoder rejects. Absent on the wire has to stay absent in the value.
            val lineNumber = if (matcher.group(5) != null) {
                matcher.group(6).toInt()
            } else {
                null
            }

            return AdvancedOkRs(planner, blockQueue, lineNumber)
        }
    }
}

object OkRsDecoder : GRsDecoder<OkRs<*>> {
    override fun match(line: String) = SimpleOkRs.match(line) || AdvancedOkRs.match(line)

    override fun decodeParams(line: String) =
        if (SimpleOkRs.match(line)) SimpleOkRs else AdvancedOkRs.decodeParams(line)
}