package org.qw3rtrun.p3d.g.protocol

/**
 * Every reply the RepRap base protocol defines, and the lookup that reads one back.
 *
 * ```
 * ok                                        SimpleOKRs
 * ok P15 B3 N100                            AdvancedOKRs
 * Resend: 66556                             ResendRs
 * Error:checksum mismatch, Last Line: 66555  ErrorRs
 * wait                                      WaitRs
 * busy: processing                          SimpleBusyRs
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
 * ```
 * BaseRsDecoder.decode("wait")     // WaitRs
 * BaseRsDecoder.decode("T:20.0")   // null - not a base protocol reply
 * ```
 *
 * @property decoders one decoder per reply kind. `ok` comes first because it is far and away the
 *   most common.
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
object BaseRsDecoder : GRsDecoder<GRs<*>> {

    val decoders: List<GRsDecoder<GRs<*>>> = listOf(
        OkRsDecoder,
        WaitRs,
        ResendRs,
        ErrorRs,
        SimpleBusyRs,
        CommentRsDecoder,
        StartRs,
    )

    /**
     * One pass down the list, stopping at the first decoder that claims the line.
     *
     * The two overrides that used to sit beside this are gone with the inversion: `match` walked
     * the whole list asking each decoder to decide, and `decodeParams` then walked it again to get
     * the value - so a line reaching here through `match`-then-`decodeParams` was read by every
     * decoder twice.
     */
    override fun decode(line: String): GRs<*>? = decoders.firstNotNullOfOrNull { it.decode(line) }
}
