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

data class GDDoubleField(
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
