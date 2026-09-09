package org.qw3rtrun.p3d.g.decoder

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.g.marlin.decoder.FirmwareReportDecoder
import java.util.UUID

class FirmwareReportDecoderTest {

    private val decoder = FirmwareReportDecoder()

    @Test
    fun `a real Marlin M115 reply decodes field by field`() {
        val input =
            "FIRMWARE_NAME:Marlin bugfix-2.1.x (Jun 21 2023 14:52:55) SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin PROTOCOL_VERSION:1.0 MACHINE_TYPE:SimRap 1.4 EXTRUDER_COUNT:1 UUID:cede2a2f-41a2-4748-9b12-c55c62f367ff"

        val report = decoder.decode(input)

        Assertions.assertTrue(report.isPresent)
        val r = report.get()
        Assertions.assertEquals("Marlin bugfix-2.1.x (Jun 21 2023 14:52:55)", r.firmwareName())
        Assertions.assertEquals("github.com/MarlinFirmware/Marlin", r.srcCodeUrl())
        Assertions.assertEquals("1.0", r.protocolVersion())
        Assertions.assertEquals("SimRap 1.4", r.machineType())
        Assertions.assertEquals(1, r.extruderCount())
        Assertions.assertEquals(UUID.fromString("cede2a2f-41a2-4748-9b12-c55c62f367ff"), r.uuid())
    }

    @Test
    fun `the prefix alone decodes to a single empty field`() {
        val report = decoder.decode("FIRMWARE_NAME:")

        Assertions.assertTrue(report.isPresent)
        Assertions.assertEquals("", report.get().firmwareName())
        Assertions.assertNull(report.get().srcCodeUrl())
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "",
        "ok",
        "Cap:AUTOREPORT_TEMP:1",
        "FIRMWARE_NAME",
        " FIRMWARE_NAME:Marlin",
        // Characterisation, not a rule: the prefix guard folds case but the field pattern is
        // [A-Z_]+, so a lower-case key passes the guard and then finds nothing. This is the
        // ignoreCase debt logged as doc/todos/06, left alone here on purpose.
        "firmware_name:Marlin"
    ])
    fun `a line that is not a firmware report yields absence`(line: String) {
        Assertions.assertFalse(decoder.decode(line).isPresent, "should not decode: $line")
    }
}
