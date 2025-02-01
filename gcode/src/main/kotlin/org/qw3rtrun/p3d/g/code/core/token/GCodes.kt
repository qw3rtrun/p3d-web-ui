package org.qw3rtrun.p3d.g.code.core.token

sealed interface GCode {
    val command: GCommand
    val fields: List<GField>
}

sealed interface GCommand {
    val letter: GIdentifier
    val literal: GLiteral
}