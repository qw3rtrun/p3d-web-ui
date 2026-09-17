package org.qw3rtrun.p3d.g.marlin.protocol

import org.qw3rtrun.p3d.g.code.dsl.GRs
import org.qw3rtrun.p3d.g.code.dsl.GRsDecoder

/**
 * Every reply the RepRap base protocol defines, and the lookup that reads one back.
 *
 * ```
 * ok                                        SimpleOKRs
 * ok P15 B3 N100                            AdvancedOKRs
 * Resend: 66556                             ResendRs
 * Error:checksum mismatch, Last Line: 66555  ErrorRs
 * wait                                      WaitRs
 * busy: processing                          BusyRs
 * start                                     StartRs
 * //action:pause                            ActionRs
 * // anything else                          DebugRs
 * ```
 *
 * The decoders partition the reply space, so [decode] does not depend on the order of [decoders]
 * and a line is claimed by at most one of them. The one pair that could overlap - `//action:` and
 * `//` - is separated inside [CommentRsDecoder].
 *
 * **A line that decodes to null is not an error.** The spec's original rule that every line
 * carries one of four two-character prefixes is called out there as obsolete, so unprefixed
 * replies are normal: `M105` answers with a bare temperature report, `M115` with
 * `FIRMWARE_NAME:...`. Those have their own decoders and are not part of this base set.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
object BaseRsDecoder : GRsDecoder<GRs<*>> {

    /** One decoder per reply kind. `ok` comes first because it is far and away the most common. */
    val decoders: List<GRsDecoder<GRs<*>>> = listOf(
        OkRsDecoder,
        WaitRs,
        ResendRs,
        ErrorRs,
        BusyRs,
        CommentRsDecoder,
        StartRs,
    )

    override fun match(line: String): Boolean = decoders.any { it.match(line) }

    override fun decodeParams(line: String): GRs<*> {
        val decoded = decoders.firstNotNullOfOrNull { it.decode(line) }
        require(decoded != null) { "not a base protocol reply: $line" }
        return decoded
    }

    override fun decode(line: String): GRs<*>? = decoders.firstNotNullOfOrNull { it.decode(line) }
}
