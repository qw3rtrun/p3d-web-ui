package org.qw3rtrun.p3d.g.code.core.token

sealed interface GSemantic

sealed interface GLine {
    val line: List<GToken>
}

data class GCommand(val head: GIdentifier, val params: List<GElement> = emptyList()) {
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

data class GCommandLine(val cmds: List<GCommand>, override val line: List<GToken>) : GLine

sealed interface GError : GLine {
    val msg: String
}

data class GNotIdentifierError(val head: GElement, override val line: List<GToken>) : GError {
    override val msg: String
        get() = "GCode should start with a letter, but '${head.rawText()}'"
}
