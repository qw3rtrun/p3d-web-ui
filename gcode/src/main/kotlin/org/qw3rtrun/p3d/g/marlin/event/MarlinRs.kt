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
 * busy: processing                      SimpleBusyRs    (base)
 * echo:busy: processing                 BusyNotice      - Marlin's spelling; both are BusyRs
 * ok T:210.00 /210.00 B:60.00 /60.00    OkTemperatureRs
 * X:0.00 Y:0.00 Z:0.00 Count X:0        PositionRs
 * x_min: open                           EndstopStateRs
 * SD printing byte 1234/56789           SdPrinting
 * FIRMWARE_NAME:Marlin ...              FirmwareInfoRs
 * Cap:EEPROM:1                          CapabilityRs
 * echo:Unknown command: "M9999"         UnknownCommand
 * echo:M104 Invalid extruder 5          InvalidExtruder
 * echo:DEBUG:INFO,ERRORS                DebugLevelRs    - M111
 * echo:Cold extrudes are enabled ...    ColdExtrusionRs - M302
 * echo: cold extrusion prevented        ColdExtrusionPrevented
 * Current file: (no file)               SdCurrentFile   - M27 C
 * Begin file list / End file list       SdFileListBegin / SdFileListEnd - M20
 * Writing to file: test.gco             SdWritingToFile - M28
 * Done saving file.                     SdDoneSaving    - M29
 * File opened: test.gco Size: 47        SdFileOpened    - M23, M32
 * File selected                         SdFileSelected  - M23, M32
 * Done printing file                    SdDonePrinting  - unbidden, at the end of a job
 * echo:SD card ok                       SdCardOk        - M21
 * echo:Now fresh file: test.gco         SdFreshFile     - M28, M32
 * ```
 *
 * **The `M20` entries between those two markers are deliberately absent**, and that is the one
 * place the registry does not cover a reply: `TEST.GCO 47` is `<word> <number>`, a shape far too
 * common to claim. `SdFileListReader` reads them, because it knows a listing is open.
 *
 * The decoders partition the reply space - a test pins that no line is claimed by two - so [decode]
 * does not depend on the order of [decoders]. The Marlin ones come first anyway, because
 * `ok T:210.00 /210.00` is a temperature report and only incidentally an `ok`.
 *
 * **Null is not an error.** Most of what a printer says is still outside this set: the whole of
 * tiers 2 to 4 in todo 12, every `M503` settings line, the boot banner. Those lines are the
 * caller's to keep as raw text.
 *
 * ```
 * MarlinRsDecoder.decode("ok T:210.00 /210.00")        // OkTemperatureRs
 * MarlinRsDecoder.decode("Reporting nothing at all")   // null
 * ```
 *
 * @property decoders tier 1 first, then the base protocol
 */
object MarlinRsDecoder : GRsDecoder<GRs<*>> {

    val decoders: List<GRsDecoder<GRs<*>>> = listOf(
        TemperatureRsDecoder,
        PositionRs,
        SdCardRsDecoder,
        FirmwareInfoRs,
        CapabilityRs,
        EchoRsDecoder,
        EndstopRsDecoder,
        BaseRsDecoder,
    )

    /**
     * One pass down the list - the whole chain, for one line, exactly once.
     *
     * This is the live path: `HostTerminal.ReplyFlux` runs it for every line a printer sends. It
     * used to carry a `match` that walked all eight decoders asking each to decide - and the two
     * dispatchers below it did the same to their own - so asking "is this anything?" before asking
     * "what is it?" read a line on the order of fifteen times.
     */
    override fun decode(line: String): GRs<*>? = decoders.firstNotNullOfOrNull { it.decode(line) }
}
