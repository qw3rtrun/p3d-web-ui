package org.qw3rtrun.p3d.g.code.core

import java.math.BigDecimal

data class GDescriptor(val letter: Char, val number: Int, val strTail: Boolean, val fields: List<GDField<*>>)
sealed interface GDField<T> {
    val letter: Char
    val default: T?
    val optional: Boolean
}

data class GDIntField(
    override val letter: Char,
    override val default: Int? = null,
    override val optional: Boolean = true
) : GDField<Int>

/**
 * A decimal-valued field. The value is a `BigDecimal`, not a `Double` - see `GNumber` for why the
 * token layer holds arbitrary-precision decimal and what a port has to replace it with. This is the
 * descriptor an encoder reads defaults from, so a default has to render as the digits it was written
 * as; scale is part of that.
 */
data class GDDecimalField(
    override val letter: Char,
    override val default: BigDecimal? = null,
    override val optional: Boolean = true
) : GDField<BigDecimal>

data class GDFlagField(
    override val letter: Char,
    override val default: Boolean? = null,
    override val optional: Boolean = true
) : GDField<Boolean>

data class GDStringField(
    override val letter: Char,
    override val default: String? = null,
    override val optional: Boolean = true
) : GDField<String>
