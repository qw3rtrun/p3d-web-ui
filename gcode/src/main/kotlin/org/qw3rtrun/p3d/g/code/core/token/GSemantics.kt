package org.qw3rtrun.p3d.g.code.core.token

sealed interface GSemantic

sealed interface GLine {
    val line: List<GToken>
}

sealed interface GError : GLine {
    val msg: String
}

data class GNotIdentifierError(val head: GElement, override val line: List<GToken>) : GError {
    override val msg: String
        get() = "GCode should start with a letter, but '${head.rawText()}'"
}
