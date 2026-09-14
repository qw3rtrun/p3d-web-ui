package org.qw3rtrun.p3d.g.code.core

/**
 * Picks the algorithm for a `*` field, per GCODE_spec.md section 8.1: **the digit count decides**.
 * 1-3 digits is the XOR checksum of section 8.2, 5 digits the CRC16 of section 8.4, and no other
 * width is a checksum field at all - RepRapFirmware rejects those outright rather than guessing.
 *
 * Returns `null` when the field is not one this module can verify, which is a syntax answer and not
 * a mismatch: nothing has been computed yet at that point.
 *
 * **Dispatch on the lexeme, never on the value.** A checksum's width is a property of the characters
 * on the wire, and the two disagree exactly where it matters: `*00057` is a five-digit CRC field
 * carrying 57, while its `int` is 57, which looks like two digits and would select the XOR checksum.
 * A CRC is zero-padded by definition (section 8.4), so reading the width off the number would
 * misroute a large share of real CRC lines.
 *
 * Keeping the dispatch here is also what stops either calculator learning that the other exists.
 */
fun checkSumCalculatorFor(lexeme: String): CheckSumCalculator? {
    // Section 8.1 spells the field `*<unsigned-int>`: digits only. A sign is not part of one, and
    // `GInt` will happily carry `+57` as a lexeme of length 3, which would otherwise be read as a
    // three-digit XOR field. RepRapFirmware's parser leaves its checksum state on the first
    // non-digit and then rejects the line, so this agrees with it.
    for (i in 0 until lexeme.length) {
        val ch = lexeme[i]
        if (ch < '0' || ch > '9') return null
    }
    return when (lexeme.length) {
        1, 2, 3 -> XorCheckSum()
        5 -> Crc16CheckSum()
        else -> null
    }
}
