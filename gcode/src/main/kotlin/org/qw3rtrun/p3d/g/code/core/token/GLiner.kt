package org.qw3rtrun.p3d.g.code.core.token

import kotlin.math.min

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

    private fun isPacket(tokens: List<GToken>): Boolean = tokens[0] == GLetter('N') && tokens.contains(GChecksum)

    private fun parseLine(tokens: List<GToken>): GLine {
        return if (tokens.isEmpty()) GEmptyLine()
        else if (isPacket(tokens)) parsePacket(tokens)
        else GSimpleLine(tokens)
    }

    private fun parsePacket(tokens: List<GToken>): GPacketLine {
        val number = if (tokens.size > 1 && tokens[1] is GInt) tokens[1] as GInt else GInt(-1)
        val indexOfCheckSum = tokens.indexOf(GChecksum)
        if (indexOfCheckSum > 1) {
            val payload = tokens.subList(2, indexOfCheckSum)
            val checkSum =
                if (tokens.size > indexOfCheckSum + 1 && tokens[indexOfCheckSum + 1] is GInt)
                    GCheckSumValue(GChecksum, tokens[indexOfCheckSum + 1] as GInt)
                else GCheckSumValue(GChecksum, GInt(-1))
            val tail = tokens.subList(indexOfCheckSum + 1, tokens.size - 1)
            return GPacketLine(number, payload, checkSum, tail)
        }
        return GPacketLine(
            number,
            tokens.subList(min(2, tokens.size), tokens.size - 1),
            GCheckSumValue(GChecksum, GInt(-1)),
            emptyList()
        )
    }

}

class GCommandParser {
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
