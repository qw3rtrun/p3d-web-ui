package org.qw3rtrun.p3d.g.marlin.protocol

import org.qw3rtrun.p3d.core.msg.OKReceivedEvent
import org.qw3rtrun.p3d.g.code.dsl.GRS
import org.qw3rtrun.p3d.g.code.dsl.GRSDecoder
import java.util.regex.Pattern

interface OKRs<D : OKRs<D>> : GRS<D>, OKReceivedEvent

object SimpleOKRs : OKRs<SimpleOKRs>, GRSDecoder<SimpleOKRs> {
    override fun encode(): String = "ok"
    override fun match(line: String): Boolean = line.trim().equals("ok", ignoreCase = true)
    override fun decodeParams(line: String): SimpleOKRs = this
}

data class AdvancedOKRs(
    val planner: Int,
    val blockQueue: Int,
    val lineNumber: Int? = null
) : OKRs<AdvancedOKRs> {

    override fun encode(): String = "ok P$planner B$blockQueue${if (lineNumber != null) " N$lineNumber" else ""}"

    companion object : GRSDecoder<AdvancedOKRs> {
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

        override fun decodeParams(line: String): AdvancedOKRs {
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
            val lineNumber = if (matcher.group(5) != null) {
                matcher.group(6).toInt()
            } else {
                -1
            }

            return AdvancedOKRs(planner, blockQueue, lineNumber)
        }
    }
}

object OKRsDecoder : GRSDecoder<OKRs<*>> {
    override fun match(line: String) = SimpleOKRs.match(line) || AdvancedOKRs.match(line)

    override fun decodeParams(line: String) =
        if (SimpleOKRs.match(line)) SimpleOKRs else AdvancedOKRs.decodeParams(line)
}