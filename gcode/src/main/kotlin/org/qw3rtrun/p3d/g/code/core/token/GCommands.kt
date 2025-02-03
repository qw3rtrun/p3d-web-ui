package org.qw3rtrun.p3d.g.code.core.token

data class GCommand (
    val head: GIdentifier,
    val tail: List<GMember>
)

sealed interface GParsedLine {
    val line: List<GToken>
}

sealed interface GError : GParsedLine {
    val msg: String
}

data class GNotIdentifierError(val head: GMember, override val line: List<GToken>) : GError {
    override val msg: String
        get() = "GCode should start with a letter, but '${head.rawText()}'"
}

data class GCommands(val cmds: List<GCommand>, override val line: List<GToken>) : GParsedLine
