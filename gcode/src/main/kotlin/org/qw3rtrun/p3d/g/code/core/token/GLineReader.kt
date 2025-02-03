package org.qw3rtrun.p3d.g.code.core.token

class GCodeReader {

//    fun read(tokens: Iterator<GToken>): Iterator<GCode> {
//
//    }

}

class GLineIterator(val tokens: Iterator<GToken>) : Iterator<GParsedLine> {


    override fun hasNext(): Boolean {
        return tokens.hasNext()
    }

    override fun next(): GParsedLine = parseLine(nextLine())

    private fun nextLine(): List<GToken> {
        val line = ArrayList<GToken>()
        while (tokens.hasNext()) {
            val next = tokens.next()
            line.add(next)
            when {
                next is GLineBreak -> break
            }
        }
        return line
    }

    private fun parseLine(tokens: List<GToken>): GParsedLine {
        val members = tokens.filter { it is GMember }.map { it as GMember }
        if (members.isEmpty()) {
            return GCommands(emptyList(), tokens)
        }

        val first = members[0]
        val cmds = ArrayList<GCommand>()
        if (first is GIdentifier && isCommand(first, true)) {
            cmds.plus(GCommand(first, members.subList(1, members.size)))
            return GCommands(cmds, tokens)
        } else return GNotIdentifierError(first, tokens)
    }

    private fun isCommand(token: GToken?, includeT: Boolean = false) = token is GLetter
            && (token.letter.equals('M', true)
            || token.letter.equals('G', true)
            || (includeT && token.letter.equals('T', true)))

}
