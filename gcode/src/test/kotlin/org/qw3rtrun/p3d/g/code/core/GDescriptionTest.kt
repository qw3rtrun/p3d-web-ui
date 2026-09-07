package org.qw3rtrun.p3d.g.code.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Tests for the command descriptor model - the schema that says which fields
 * (GCODE_spec.md section 4.2) a command accepts and with which value type (section 3).
 *
 * `GDescriptor` is also consumed from Java (`GAwareDecoder`), so the property names are part of its
 * contract.
 */
class GDescriptionTest {

    @Nested
    inner class Fields {

        @Test
        fun `an int field defaults to optional without a default value`() {
            val field = GDIntField('S')

            assertEquals('S', field.letter)
            assertNull(field.default)
            assertTrue(field.optional)
        }

        @Test
        fun `an int field can be required with a default`() {
            val field = GDIntField('S', 200, optional = false)

            assertEquals('S', field.letter)
            assertEquals(200, field.default)
            assertFalse(field.optional)
        }

        @Test
        fun `a double field carries a big decimal default`() {
            val field = GDDoubleField('X', BigDecimal("10.50"))

            assertEquals('X', field.letter)
            assertEquals(BigDecimal("10.50"), field.default)
            assertTrue(field.optional)
        }

        @Test
        fun `a flag field carries a boolean default`() {
            // GCODE_spec.md section 3.2 - a letter with no value is a flag.
            val field = GDFlagField('H', default = true)

            assertEquals('H', field.letter)
            assertEquals(true, field.default)
        }

        @Test
        fun `a string field carries a string default`() {
            val field = GDStringField('P', "file.gco")

            assertEquals('P', field.letter)
            assertEquals("file.gco", field.default)
        }

        @Test
        fun `fields with the same letter and default are equal`() {
            assertEquals(GDIntField('S', 1), GDIntField('S', 1))
            assertFalse(GDIntField('S', 1) == GDIntField('S', 2))
            assertFalse(GDIntField('S', 1) == GDIntField('P', 1))
        }

        @Test
        fun `all field kinds share the field interface`() {
            val fields: List<GDField<*>> = listOf(
                GDIntField('S'),
                GDDoubleField('X'),
                GDFlagField('H'),
                GDStringField('P')
            )

            assertEquals(listOf('S', 'X', 'H', 'P'), fields.map { it.letter })
            assertTrue(fields.all { it.optional })
            assertTrue(fields.all { it.default == null })
        }
    }

    @Nested
    inner class Descriptors {

        @Test
        fun `a descriptor names the command letter number and its fields`() {
            val descriptor = GDescriptor(
                letter = 'M',
                number = 104,
                strTail = false,
                fields = listOf(GDIntField('S'), GDIntField('T'))
            )

            assertEquals('M', descriptor.letter)
            assertEquals(104, descriptor.number)
            assertFalse(descriptor.strTail)
            assertEquals(listOf('S', 'T'), descriptor.fields.map { it.letter })
        }

        @Test
        fun `a descriptor can declare a trailing string command`() {
            // GCODE_spec.md section 3.4a - commands such as M117 take the rest of the line.
            val descriptor = GDescriptor('M', 117, true, emptyList())

            assertTrue(descriptor.strTail)
            assertEquals(emptyList<GDField<*>>(), descriptor.fields)
        }

        @Test
        fun `a descriptor can mix field types`() {
            val descriptor = GDescriptor(
                'G', 1, false,
                listOf(GDDoubleField('X'), GDDoubleField('Y'), GDIntField('F'), GDFlagField('S'))
            )

            assertEquals(4, descriptor.fields.size)
            assertEquals(1, descriptor.fields.count { it is GDIntField })
            assertEquals(2, descriptor.fields.count { it is GDDoubleField })
            assertEquals(1, descriptor.fields.count { it is GDFlagField })
        }

        @Test
        fun `descriptors with the same content are equal`() {
            val fields = listOf(GDIntField('S'))

            assertEquals(GDescriptor('M', 104, false, fields), GDescriptor('M', 104, false, fields))
            assertFalse(GDescriptor('M', 104, false, fields) == GDescriptor('M', 140, false, fields))
        }
    }
}
