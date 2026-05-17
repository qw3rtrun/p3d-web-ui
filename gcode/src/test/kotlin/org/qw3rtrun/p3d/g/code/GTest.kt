package org.qw3rtrun.p3d.g.code

import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.token.toToken

class GTest {

    val G = G { println(it.print()) }

    @Test
    fun test() {
        G.G(11, 'F'.toToken(), "Hello!".toToken())
    }

}
