package org.qw3rtrun.p3d.g.code.dsl

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.XorCheckSum
import org.qw3rtrun.p3d.g.code.core.token.GLiner
import org.qw3rtrun.p3d.g.code.core.token.GTokenizer
import org.qw3rtrun.p3d.g.code.core.token.GUnnamedStr
import org.qw3rtrun.p3d.g.code.core.token.GUnquotedString
import org.qw3rtrun.p3d.g.marlin.MarlinCommands
import org.qw3rtrun.p3d.g.marlin.command.SerialPrint
import org.qw3rtrun.p3d.g.marlin.command.SetLCDMessage

class M117DecoderTest {


    fun line(str: String) = GLiner(GTokenizer().parse(str.iterator())).next().body.asSequence()
    fun decode(gcode: String) = MarlinCommands.decode(line(gcode))
    fun crc(gcode: String) = XorCheckSum().let { it.add(gcode); it.get() }
    fun pack(gcode: String) = "${gcode}*${crc(gcode).rawText()}"

    @Test
    fun M117() {
        Assertions.assertEquals(
            decode("M117 Hello World")!!.encode().params[0],
            GUnnamedStr(GUnquotedString("Hello World"))
        )
        Assertions.assertEquals(decode("M117 Hello World"), SetLCDMessage("Hello World"))
        Assertions.assertEquals((decode("M117 Hello World") as SetLCDMessage).message, "Hello World")
        Assertions.assertEquals((decode("M117 H1 ello World") as SetLCDMessage).message, "H1 ello World")
        Assertions.assertEquals((decode("M117 H1ello World") as SetLCDMessage).message, "H1ello World")

        Assertions.assertEquals((decode("M118 Hello World") as SerialPrint).message, "Hello World")
        Assertions.assertEquals((decode("M118 P1 ello World") as SerialPrint).message, "ello World")
        Assertions.assertEquals((decode("M118 P1ello World") as SerialPrint).message, "ello World")
        Assertions.assertEquals((decode("M118P1ello World") as SerialPrint).message, "ello World")
        Assertions.assertEquals((decode("M118 H1 ello World") as SerialPrint).message, "H1 ello World")
        Assertions.assertEquals((decode("M118 Hello World P1") as SerialPrint).message, "Hello World P1")
    }

    /** spec 3.4a: the string runs to the end of the line, and `;` is the one thing that ends it. */
    @Test
    fun `where the message stops`() {
        Assertions.assertEquals(SetLCDMessage("Hi"), decode("M117 Hi ; and a comment"))
        // A `( )` comment is content here - spec 3.4a names only `;` - and so is every character
        // the lexer could not make a token of.
        Assertions.assertEquals(SetLCDMessage("Hi (there) 50%"), decode("M117 Hi (there) 50%"))
        // Nothing after the code is not an empty message, it is no message at all.
        Assertions.assertEquals(SetLCDMessage(), decode("M117"))
        Assertions.assertEquals(SetLCDMessage(), decode("M117   "))
    }

    /** The other half: what is built is what a printer receives, and it reads back the same. */
    @Test
    fun `a message round-trips through the wire`() {
        val text = GEncoder.encode(SetLCDMessage("Hello World").encode())
        Assertions.assertEquals("M117 Hello World", text)
        Assertions.assertEquals(SetLCDMessage("Hello World"), decode(text))
        // Framed, the checksum covers the message like any other payload byte (spec 8.3).
        Assertions.assertEquals(
            pack("N5 M117 Hello World"),
            GEncoder.frame(5, SetLCDMessage("Hello World").encode()),
        )
    }

    /**
     * A lettered parameter in front of the string is read first, which is Marlin's own rule:
     * `M118 P0 Hello` is `P0` and a message, not a message beginning with `P0`.
     */
    @Test
    fun `lettered parameters come first`() {
        Assertions.assertEquals(SerialPrint(p = 0, message = "Hello"), decode("M118 P0 Hello"))
        Assertions.assertEquals(
            "M118 P0 Hello",
            GEncoder.encode(SerialPrint(p = 0, message = "Hello").encode()),
        )
    }
}