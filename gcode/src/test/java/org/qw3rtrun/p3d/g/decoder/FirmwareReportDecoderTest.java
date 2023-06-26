package org.qw3rtrun.p3d.g.decoder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FirmwareReportDecoderTest {

    private FirmwareReportDecoder decoder = new FirmwareReportDecoder();

    @Test
    void test() {
        var input = "FIRMWARE_NAME:Marlin bugfix-2.1.x (Jun 21 2023 14:52:55) SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin PROTOCOL_VERSION:1.0 MACHINE_TYPE:SimRap 1.4 EXTRUDER_COUNT:1 UUID:cede2a2f-41a2-4748-9b12-c55c62f367ff";

        decoder.decode(input);
    }
}
