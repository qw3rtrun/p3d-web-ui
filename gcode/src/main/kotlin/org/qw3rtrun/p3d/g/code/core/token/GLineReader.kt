package org.qw3rtrun.p3d.g.code.core.token

class GCodeReader {

//    fun read(tokens: Iterator<GToken>): Iterator<GCode> {
//
//    }

}

class GLineIterator(val tokens: Iterator<GToken>) : Iterator<GLine> {


    override fun hasNext(): Boolean {
        return tokens.hasNext()
    }

    override fun next(): GLine = parseLine(nextLine())

    private fun nextLine(): List<GToken> {
        val line = ArrayList<GToken>()
        do {
            val next = tokens.next()
            line.add(next)
            when {
                next is GLineBreak -> break
            }
        } while (tokens.hasNext())
        return line
    }

    private fun parseLine(tokens: List<GToken>): GLine {
        val members = tokens.filter { it is GElement }.map { it as GElement }.iterator()
        if (!members.hasNext()) {
            return GCommandLine(emptyList(), tokens)
        }

        var cmds = listOf<GCommand>()
        val first = members.next()
        if (first is GIdentifier && isCommand(first, true)) {
            var cmd: GIdentifier = first
            var params = mutableListOf<GElement>()
            while (members.hasNext()) {
                val next = members.next()
                if (next is GIdentifier && isCommand(next, false)) {
                    cmds = cmds + GCommand(cmd, params)
                    cmd = next
                    params = mutableListOf<GElement>()
                } else {
                    params.add(next)
                }
            }
            return GCommandLine(cmds + GCommand(cmd, params), tokens)
        } else return GNotIdentifierError(first, tokens)
    }

    private fun isCommand(token: GToken?, includeT: Boolean = false) = token is GLetter
            && (token.letter.equals('M', true)
            || token.letter.equals('G', true)
            || (includeT && token.letter.equals('T', true)))

}
