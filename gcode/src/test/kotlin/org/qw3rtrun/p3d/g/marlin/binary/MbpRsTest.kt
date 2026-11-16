package org.qw3rtrun.p3d.g.marlin.binary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder
import org.qw3rtrun.p3d.g.protocol.GRs
import org.qw3rtrun.p3d.g.protocol.ResendRs

/**
 * Cover for what the firmware says back in binary mode.
 *
 * **Every literal here is a line `binary_stream.h` writes**, read off the firmware source
 * (bugfix-2.1.x) rather than off the Python reference, because the reference does not write these
 * lines and in two places does not read them correctly either. The `SERIAL_ECHOLNPGM("ok", sync)`
 * form with no separator, the `ss<n>,<buffer>,<version>` triple, the `PFT:version:...` clause and
 * the transposed `PTF:invalid` all come from there.
 */
class MbpRsTest {

    /** The whole family, in the spelling the firmware uses, with what each decodes to. */
    private val lines: List<Pair<String, MbpRs<*>>> = listOf(
        "ok0" to MbpOk(0),
        "ok7" to MbpOk(7),
        "ok255" to MbpOk(255),
        "rs0" to MbpResend(0),
        "rs42" to MbpResend(42),
        "fe3" to MbpFatalError(3),
        "ss0,512,0.1.0" to MbpStreamSync(0, 512, "0.1.0"),
        "ss137,96,0.1.0" to MbpStreamSync(137, 96, "0.1.0"),
        "PFT:version:0.1.0:compression:none" to MbpTransferVersion("0.1.0", "none", 0, 0),
        "PFT:version:0.1.0:compression:heatshrink,8,4" to
            MbpTransferVersion("0.1.0", "heatshrink", 8, 4),
        "PFT:success" to MbpTransferSuccess,
        "PFT:fail" to MbpTransferFail,
        "PFT:busy" to MbpTransferBusy,
        "PFT:ioerror" to MbpTransferIoError,
        "PFT:invalid" to MbpTransferInvalid,
        "PTF:invalid" to MbpUnknownPacketType,
    )

    @Test
    fun `every line decodes through the registry`() {
        for ((line, expected) in lines) {
            assertEquals(expected, MbpRsDecoder.decode(line)) { "failed for: $line" }
        }
    }

    @Test
    fun `every one of them round-trips`() {
        for ((line, expected) in lines) {
            assertEquals(line, expected.encode()) { "encode differs for: $line" }
            assertEquals(expected, MbpRsDecoder.decode(expected.encode()))
        }
    }

    @Test
    fun `no line is claimed by two decoders`() {
        for ((line, _) in lines) {
            val claimants = MbpRsDecoder.decoders.filter { it.match(line) }
            assertEquals(1, claimants.size) { "${claimants.size} decoders claim: $line" }
        }
    }

    @Test
    fun `all of them are one family a host in binary mode can match in one place`() {
        for ((line, _) in lines) {
            assertTrue(MbpRsDecoder.decode(line) is MbpRs<*>) { "not an MbpRs: $line" }
            assertTrue(MbpRsDecoder.decode(line) is GRs<*>) { "not a GRs: $line" }
        }
    }

    /**
     * The reason this family is kept out of `MarlinRsDecoder`: one of its lines is already a valid
     * base-protocol reply, and the two number different things.
     */
    @Nested
    inner class NotInTheMainRegistry {

        @Test
        fun `rs7 is a base-protocol Resend for line 7 and a binary resend for packet 7`() {
            // Measured: the ASCII registry reads it as a G-code line number, this one as a packet
            // sync id, and no text distinguishes them. A host has to know which mode it is in.
            assertEquals(ResendRs(7), MarlinRsDecoder.decode("rs7"))
            assertEquals(MbpResend(7), MbpRsDecoder.decode("rs7"))
        }

        @Test
        fun `every other binary line falls through the ASCII registry`() {
            for ((line, _) in lines) {
                if (line.startsWith("rs")) continue
                assertNull(MarlinRsDecoder.decode(line)) { "the ASCII registry claims: $line" }
            }
        }

        @Test
        fun `and the ASCII handshake replies fall through this one`() {
            // The bare ok that answers M28 B1, and the echo line that confirms the switch.
            assertNull(MbpRsDecoder.decode("ok"))
            assertNull(MbpRsDecoder.decode("echo:Switching to Binary Protocol"))
            assertNotNull(MarlinRsDecoder.decode("ok"))
            assertNotNull(MarlinRsDecoder.decode("echo:Switching to Binary Protocol"))
        }
    }

    /**
     * The three one-field stream replies share a scanner, so what it accepts is pinned once.
     */
    @Nested
    inner class SyncIds {

        @ParameterizedTest
        @ValueSource(strings = ["ok", "ok ", "ok 7", "ok+7", "ok-7", "ok7 8", "ok7x", "okay", "o7"])
        fun `a line that is not the keyword then digits then nothing is declined`(line: String) {
            assertNull(MbpOk.decode(line)) { "claimed: $line" }
        }

        @Test
        fun `an id a uint8 could not have written is declined`() {
            assertNull(MbpOk.decode("ok256"))
            assertNull(MbpResend.decode("rs1000"))
            assertNull(MbpFatalError.decode("fe99999999999999999999"))
            assertEquals(MbpOk(255), MbpOk.decode("ok255"))
        }

        @Test
        fun `surrounding whitespace and letter case are tolerated`() {
            assertEquals(MbpOk(7), MbpOk.decode(" ok7\r\n"))
            assertEquals(MbpOk(7), MbpOk.decode("OK7"))
            assertEquals(MbpResend(7), MbpResend.decode("\tRS7 "))
        }
    }

    /** The handshake reply, which carries three fields and is the only one with structure. */
    @Nested
    inner class StreamSync {

        @Test
        fun `the firmware's spelling decodes`() {
            assertEquals(MbpStreamSync(0, 512, "0.1.0"), MbpStreamSync.decode("ss0,512,0.1.0"))
        }

        @Test
        fun `a blank after the keyword is tolerated`() {
            assertEquals(MbpStreamSync(0, 512, "0.1.0"), MbpStreamSync.decode("ss 0,512,0.1.0"))
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "ss", "ss0", "ss0,512", "ss0,512,", "ss,512,0.1.0", "ss0,,0.1.0",
                "ss0 512 0.1.0", "ss256,512,0.1.0", "ssx,512,0.1.0", "ss0,-1,0.1.0",
            ]
        )
        fun `anything short of three well-formed fields is declined`(line: String) {
            assertNull(MbpStreamSync.decode(line)) { "claimed: $line" }
        }

        @Test
        fun `a buffer wider than the protocol's payload field is declined`() {
            assertNull(MbpStreamSync.decode("ss0,65536,0.1.0"))
            assertEquals(
                MbpStreamSync(0, 65535, "0.1.0"),
                MbpStreamSync.decode("ss0,65535,0.1.0"),
            )
        }

        @Test
        fun `the version is kept as written, so a future one round-trips`() {
            val reply = MbpStreamSync.decode("ss0,512,1.2.3-rc1")
            assertEquals(MbpStreamSync(0, 512, "1.2.3-rc1"), reply)
            assertEquals("ss0,512,1.2.3-rc1", reply?.encode())
        }
    }

    /** `PFT:version:`, the one reply with an optional clause. */
    @Nested
    inner class TransferVersion {

        @Test
        fun `a client with no compression`() {
            assertEquals(
                MbpTransferVersion("0.1.0", "none", 0, 0),
                MbpTransferVersion.decode("PFT:version:0.1.0:compression:none"),
            )
        }

        @Test
        fun `a client with heatshrink reports its window and lookahead in bits`() {
            assertEquals(
                MbpTransferVersion("0.1.0", "heatshrink", 8, 4),
                MbpTransferVersion.decode("PFT:version:0.1.0:compression:heatshrink,8,4"),
            )
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "PFT:version:", "PFT:version:0.1.0", "PFT:version::compression:none",
                "PFT:version:0.1.0:compression:", "PFT:version:0.1.0:compression:heatshrink,8",
                "PFT:version:0.1.0:compression:heatshrink,x,4",
                "PFT:version:0.1.0:compression:heatshrink,8,",
                "PFT:version:0.1.0:compression:,8,4",
                "PFT:version:0.1.0:compression:heatshrink,0,0",
            ]
        )
        fun `a malformed clause is declined rather than half-read`(line: String) {
            assertNull(MbpTransferVersion.decode(line)) { "claimed: $line" }
        }
    }

    /**
     * The `PFT:`/`PTF:` pair, which is the firmware's own typo and not this module's.
     */
    @Nested
    inner class TheTransposedTypo {

        @Test
        fun `both spellings exist on the wire and mean different things`() {
            // binary_stream.h: PFT:invalid for a close or write with no open file,
            // PTF:invalid on the default branch for an unknown packet type.
            assertEquals(MbpTransferInvalid, MbpRsDecoder.decode("PFT:invalid"))
            assertEquals(MbpUnknownPacketType, MbpRsDecoder.decode("PTF:invalid"))
        }

        @Test
        fun `neither re-encodes to the other's spelling`() {
            // The bug in both prior implementations: the Python reference registers only PTF: and
            // then compares against PFT:, so its "no open file" branch is unreachable; kmbp
            // registered only PFT: and lost the unknown-type reply instead.
            assertEquals("PFT:invalid", MbpTransferInvalid.encode())
            assertEquals("PTF:invalid", MbpUnknownPacketType.encode())
            assertNull(MbpTransferInvalid.decode("PTF:invalid"))
            assertNull(MbpUnknownPacketType.decode("PFT:invalid"))
        }
    }

    @Test
    fun `lines from other protocols are declined`() {
        val others = listOf(
            "", " ", "ok", "wait", "start", "busy: processing", "Resend: 12", "rs N12",
            "T:210.00 /210.00", "echo:busy", "Error:No Checksum", "PFT:", "PFT:unknown",
            "ss", "ok T:210.00 /210.00", "File selected",
        )
        for (line in others) {
            assertNull(MbpRsDecoder.decode(line)) { "claimed: '$line'" }
        }
    }
}
