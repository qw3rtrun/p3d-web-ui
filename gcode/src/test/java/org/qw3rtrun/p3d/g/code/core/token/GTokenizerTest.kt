package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Test

class GTokenizerTest {

    @Test fun test() {
        println(GTokenizer("M155 F1 X1.05\n").parse())
        println(GTokenizer("M155F1X1.05 \n").parse())
        println(GTokenizer("M11 I   \"Hello World\"").parse())
        println(GTokenizer("M11I\"Hello World\"").parse())
        println(GTokenizer("M11I\"Hello \"\"World\"\"\"").parse())
        println(GTokenizer("M{SHOW_MGS}I\"Hello \"\"World\"\"\"").parse())
        println(GTokenizer("M{SET_TEMP} F{CURRENT}X1.05\n").parse())

    }

}
