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
 * <p>The same contract {@link EventStreamContractTest} keeps for the event stream, kept here for
 * the one REST payload the UI reads field by field. It exists because the browser's description of
 * this record had <strong>six</strong> fields while the record had seven, and the one it was
 * missing was {@code slice} - so the estimated print time and the filament length were read out of
 * the file's comments, stored, serialised onto the wire and then dropped on the floor by a
 * TypeScript interface that had never heard of them. Nothing in either build said a word: the Java
 * side compiles against the record, {@code vue-tsc} compiles against the interface, and neither
 * can see the other.
 *
 * <p><strong>Five fields on {@code slice}, not four.</strong> {@code SliceInfo} is a record of four
 * components, but {@code isKnown()} is a public getter and Jackson serialises it as a property - so
 * the wire carries a discriminator the record's parameter list does not mention. A mirror written
 * from the record alone would miss it, which is the same class of mistake over again; this pins it.
 *
 * <p>Asserted against a plain mapper rather than through a running context on purpose. What is
 * under test is the shape Jackson gives these two records - the names, and whether a {@code Long}
 * and a {@code BigDecimal} arrive as JSON numbers or as strings - and booting a context would prove
 * the same thing more slowly and with more that could go wrong on the way.
 */
class GcodeFileContractTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private static GcodeFile file(SliceInfo slice) {
        return new GcodeFile(UUID.randomUUID(), "benchy.gcode", "a".repeat(64),
                2_847_561L, 73_204L, LocalDateTime.of(2026, 11, 2, 16, 45, 26), slice);
    }

    /**
     * The seven components, under the names the browser's {@code GcodeFile} declares.
     *
     * <p>{@code sizeBytes} is the denominator an SD transfer's progress bar divides by, and
     * {@code slice} is the field that was missing.
     */
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

    /**
     * What a Cura header becomes on the wire - numbers, and all five names.
     *
     * <p>{@code ;TIME:3661} and {@code ;Filament used: 1.2345m} in the file; {@code 3661} and
     * {@code 1234.5000} here. <strong>The length is in millimetres whatever unit the file used</strong>,
     * and the scale {@code BigDecimal} keeps survives into the response text - which is exactly why
     * the UI must format it rather than print it: "1234.5 mm" is not what anybody asked.
     */
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

    /**
     * A file nothing could be read from, which is a value and not an absence.
     *
     * <p>{@code slice} is still an object - {@code GcodeFile}'s compact constructor substitutes
     * {@link SliceInfo#unknown()} for a null, so no caller on either side of the wire has to check
     * for one - and every field in it is null. A page reads {@code known} to decide what to say,
     * and what it must not say is zero: a print that takes no time and uses no filament is a
     * different claim from nobody having written one down.
     */
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

    /** One value read out of a file is enough to make the summary worth showing. */
    @Test
    void aPartialSummaryIsKnown() {
        var slice = mapper.readTree(mapper.writeValueAsString(
                file(new SliceInfo(null, 3661L, null, null)))).get("slice");

        assertTrue(slice.get("known").booleanValue());
        assertTrue(slice.get("slicer").isNull(), "and the fields nobody stated stay null");
    }
}
