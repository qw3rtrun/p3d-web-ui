package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.SliceInfo;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON the browser actually receives from {@code /api-files}.
 *
 * <p>The same kind of contract {@link EventStreamContractTest} keeps for the event stream, for the
 * one REST payload the UI reads field by field. It exists because the browser's description of this
 * record once had six fields while the record had seven - missing {@code slice} - so estimated print
 * time and filament length were stored and serialised and then dropped on the floor by a TypeScript
 * interface that had never heard of them, with neither build able to see the other.
 *
 * <p>Five fields on {@code slice}, not four: {@code SliceInfo} is a record of four components, but
 * {@code isKnown()} is a public getter Jackson serialises as a property, so the wire carries a
 * discriminator the parameter list does not mention.
 *
 * <p>Asserted against a plain mapper rather than a running context: what is under test is the shape
 * Jackson gives these two records, and booting a context would prove the same thing more slowly.
 */
class GcodeFileContractTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private static GcodeFile file(SliceInfo slice) {
        return new GcodeFile(UUID.randomUUID(), "benchy.gcode", "a".repeat(64),
                2_847_561L, 73_204L, LocalDateTime.of(2026, 11, 2, 16, 45, 26), slice);
    }

    // The seven components, under the names the browser's GcodeFile declares. sizeBytes is the
    // denominator an SD transfer's progress bar divides by; slice is the field that was missing.
    @Test
    void aStoredFileCarriesAllSevenOfItsComponents() {
        var payload = mapper.readTree(mapper.writeValueAsString(file(SliceInfo.unknown())));

        assertEquals("benchy.gcode", payload.get("name").asString());
        assertEquals("a".repeat(64), payload.get("contentHash").asString());
        assertEquals(2_847_561L, payload.get("sizeBytes").asLong());
        assertEquals(73_204L, payload.get("lineCount").asLong());
        assertEquals(36, payload.get("id").asString().length(), "a UUID as text");
        assertTrue(payload.get("uploadedAt").asString().startsWith("2026-11-02T"),
                "a timestamp as an ISO string, not an array of numbers");
        assertTrue(payload.has("slice"), "what the slicer said reaches the browser at all");
    }

    // ;TIME:3661 and ;Filament used: 1.2345m in the file become 3661 and 1234.5000 here. The length
    // is in millimetres whatever unit the file used, and the BigDecimal scale survives into the
    // response text, which is why the UI must format it rather than print it.
    @Test
    void whatTheSlicerSaidArrivesAsNumbersUnderFiveNames() {
        var json = mapper.writeValueAsString(file(new SliceInfo("Cura_SteamEngine 5.2.1",
                3661L, new BigDecimal("1234.5000"), new BigDecimal("0.2"))));

        var slice = mapper.readTree(json).get("slice");

        assertEquals("Cura_SteamEngine 5.2.1", slice.get("slicer").asString());

        assertTrue(slice.get("estimatedSeconds").isNumber(), "a Long, not a formatted duration");
        assertEquals(3661L, slice.get("estimatedSeconds").asLong());

        assertTrue(slice.get("filamentMillimetres").isNumber(), "a BigDecimal, not a string");
        assertEquals(1234.5, slice.get("filamentMillimetres").asDouble());
        assertTrue(json.contains("\"filamentMillimetres\":1234.5000"),
                "the scale travels, so JSON.parse yields 1234.5 and never a string");

        assertTrue(slice.get("layerHeight").isNumber());
        assertEquals(0.2, slice.get("layerHeight").asDouble());

        assertTrue(slice.get("known").booleanValue(),
                "isKnown() is a property on the wire - the fifth field a mirror has to carry");
    }

    // slice is still an object - GcodeFile's compact constructor substitutes SliceInfo#unknown()
    // for a null - with every field in it null. A page must not read that as zero: a print taking
    // no time is a different claim from nobody having written one down.
    @Test
    void aFileWithNoSlicerCommentsSaysSoWithNullsAndKnownFalse() {
        var slice = mapper.readTree(mapper.writeValueAsString(file(null))).get("slice");

        assertFalse(slice.isNull(), "never null, so the browser checks known instead");
        assertTrue(slice.get("slicer").isNull());
        assertTrue(slice.get("estimatedSeconds").isNull(), "not 0 - nobody said");
        assertTrue(slice.get("filamentMillimetres").isNull());
        assertTrue(slice.get("layerHeight").isNull());
        assertFalse(slice.get("known").booleanValue());
    }

    // One value read out of a file is enough to make the summary worth showing.
    @Test
    void aPartialSummaryIsKnown() {
        var slice = mapper.readTree(mapper.writeValueAsString(
                file(new SliceInfo(null, 3661L, null, null)))).get("slice");

        assertTrue(slice.get("known").booleanValue());
        assertTrue(slice.get("slicer").isNull(), "and the fields nobody stated stay null");
    }
}
