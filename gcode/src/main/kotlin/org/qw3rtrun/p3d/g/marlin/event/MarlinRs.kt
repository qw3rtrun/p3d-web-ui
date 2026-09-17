package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.GRs
import org.qw3rtrun.p3d.g.protocol.BaseRsDecoder

/**
 * Every reply this module can read: the RepRap base protocol, plus Marlin's own.
 *
 * ```
 * ok                                    SimpleOKRs      (base)
 * Resend: 66556                         ResendRs        (base)
 * busy: processing                      BusyRs          (base)
 * ok T:210.00 /210.00 B:60.00 /60.00    OkTemperatureRs
 * X:0.00 Y:0.00 Z:0.00 Count X:0        PositionRs
 * x_min: open                           EndstopStateRs
 * SD printing byte 1234/56789           SdPrinting
 * FIRMWARE_NAME:Marlin ...              FirmwareInfoRs
 * Cap:EEPROM:1                          CapabilityRs
 * echo:Unknown command: "M9999"         UnknownCommand
 * ```
 *
 * The decoders partition the reply space - a test pins that no line is claimed by two - so [decode]
 * does not depend on the order of [decoders]. The Marlin ones come first anyway, because
 * `ok T:210.00 /210.00` is a temperature report and only incidentally an `ok`.
 *
 * **Null is not an error.** Most of what a printer says is still outside this set: the whole of
 * tiers 2 to 4 in todo 12, every `M503` settings line, the boot banner. Those lines are the
 * caller's to keep as raw text.
 */
object MarlinRsDecoder : GRsDecoder<GRs<*>> {

    /** Tier 1 first, then the base protocol. */
    val decoders: List<GRsDecoder<GRs<*>>> = listOf(
        TemperatureRsDecoder,
        PositionRs,
        SdStatusRsDecoder,
        FirmwareInfoRs,
        CapabilityRs,
        EchoRsDecoder,
        EndstopRsDecoder,
        BaseRsDecoder,
    )

    override fun match(line: String): Boolean = decoders.any { it.match(line) }

    override fun decodeParams(line: String): GRs<*> {
        val decoded = decode(line)
        require(decoded != null) { "not a Marlin reply: $line" }
        return decoded
    }

    override fun decode(line: String): GRs<*>? = decoders.firstNotNullOfOrNull { it.decode(line) }
}
