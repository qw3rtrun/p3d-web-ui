package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Test
import java.util.stream.Collectors
import kotlin.streams.asStream

class GTokenizerTest {

    @Test
    fun simple() {
        println(GTokenizer("M155 F1 X1.05\n").parse())
        println(GTokenizer("M155F1X1.05 \n").parse())
        println(GTokenizer("M11 I   \"Hello World\"").parse())
        println(GTokenizer("M11I\"Hello World\"").parse())
        println(GTokenizer("M11I\"Hello \"\"World\"\"\"").parse())
        println(GTokenizer("M155\n \"Hello World\";comment\"a").parse())
        println(GTokenizer("M{SHOW_MGS}I\"Hello \"\"World\"\"\"").parse())
        println(GTokenizer("M{SET_TEMP} F{CURRENT}X1.05\n").parse())
    }

    @Test
    fun iterator() {
        fun parse(input: String) = GTokenizerIterator(input.iterator()).asSequence().asStream()
        parse("M155 F1 X1.05\n").map { it.toString() }.collect(Collectors.joining("[", ",", "]"))
        println(parse("M155F1X1.05 \n"))
        println(parse("M11 I   \"Hello World\""))
        println(parse("M11I\"Hello World\""))
        println(parse("M11I\"Hello \"\"World\"\"\""))
        println(parse("M155\n \"Hello World\";comment\"a"))
        println(parse("M{SHOW_MGS}I\"Hello \"\"World\"\"\""))
        println(parse("M{SET_TEMP} F{CURRENT:{\"a\":3}}X1.05\n"))
    }

}
