package org.qw3rtrun.p3d.g.code.core.token

sealed interface GSemantic

data class GCommand(
    val head: GIdentifier,
    val tail: List<GElement>
) : GSemantic {
    fun print() = head.rawText() + tail.joinToString("") { it.rawText() }
}

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

data class GCommandLine(val cmds: List<GCommand>, override val line: List<GToken>) : GLine

fun GIdentifier.toG(cmd: GElement, vararg elems: GElement) = GCommand(this, listOf(cmd, *elems))
