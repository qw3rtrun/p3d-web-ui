package org.qw3rtrun.p3d.g.code.token

import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.token.GCommandLine
import org.qw3rtrun.p3d.g.code.core.token.GError
import org.qw3rtrun.p3d.g.code.core.token.GLineIterator
import org.qw3rtrun.p3d.g.code.core.token.GTokenizer

class GCodeReaderTest {

    val tokenizer = GTokenizer()

    @Test
    fun testIter() {
        val input = "M155 F1 X1.05\n" +
                "M155F1X1.05 \n" +
                "M11 I   \"Hello World\"G155 \"Hello World\";comment\"\n" +
                "M{SET_TEMP} (Это \"переменные\" в (в рантайме)) F{CURRENT}X1.05"

        val iter = GLineIterator(tokenizer.parse(input).iterator()).asSequence()

        iter.forEach {
            when (it) {
                is GCommandLine -> {println(it.cmds); println(it.line)}
                is GError -> println(it.msg)
            }
        }
    }


}
