package org.qw3rtrun.p3d.g.code.core.token

sealed interface GSemantic

sealed interface GLine : GSemantic {
    val payload: List<GToken>
}

sealed interface GOrdered : GLine {
    val number: GInt
}

data class GCheckSumValue(val ident: GChecksum, val value: GInt)

sealed interface GCheckSumControlled : GLine {
    val checksum: GCheckSumValue
}

data class GEmptyLine(override val payload: List<GToken>) : GLine {
    constructor() : this(emptyList())
}

data class GSimpleLine(override val payload: List<GToken>) : GLine

data class GPacketLine(
    override val number: GInt,
    override val payload: List<GToken>,
    override val checksum: GCheckSumValue,
    val tail: List<GToken>,
) : GLine, GOrdered, GCheckSumControlled

data class GCommand(val head: GIdentifier, val params: List<GElement> = emptyList()) : GSemantic {
    constructor(headPair: Pair<GIdentifier, GElement>, params: List<GElement> = emptyList()) : this(
        headPair.first,
        listOf(headPair.second) + params
    )

    fun print(): String = buildString {
        append(head.rawText())
        for (param in params) {
            append(param.rawText())
        }
    }
}

data class GCommandLine(val cmds: List<GCommand>, override val payload: List<GToken>) : GLine

sealed interface GError : GLine {
    val msg: String
}

data class GNotIdentifierError(val head: GElement, override val payload: List<GToken>) : GError {
    override val msg: String
        get() = "GCode should start with a letter, but '${head.rawText()}'"
}
