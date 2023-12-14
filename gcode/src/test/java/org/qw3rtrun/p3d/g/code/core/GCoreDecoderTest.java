package org.qw3rtrun.p3d.g.code.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qw3rtrun.p3d.g.code.cmd.G.*;

class GCoreDecoderTest {

    @Test
    void parse() {
        assertEquals(M(155).asElements(), new GCoreDecoder("M155", new XorCheckSum()).parse());
        assertEquals(M(105, S(250)).asElements(), new GCoreDecoder("M105 S250", new XorCheckSum()).parse());
        assertEquals(M(105, B(), S(250)).asElements(), new GCoreDecoder("M105 B S250", new XorCheckSum()).parse());
        assertEquals(M(105, F("ile.gco")).asElements(), new GCoreDecoder("M105 File.gco", new XorCheckSum()).parse());
        assertEquals(M(105, COM("Comment"), B(), S(250), F("ile.gco")).asElements(), new GCoreDecoder("M105 B S250 File.gco ;Comment", new XorCheckSum()).parse());

        assertEquals(M(105, LIT("\"File.gco\"")).asElements(), new GCoreDecoder("M105 \"File.gco\"", new XorCheckSum()).parse());
        assertEquals(M(105, LIT("File new.gco")).asElements(), new GCoreDecoder("M105 \"File new.gco\"", new XorCheckSum()).parse());
        assertEquals(M(105, LIT("File\"new.gco")).asElements(), new GCoreDecoder("M105 \"File\"\"new.gco\"", new XorCheckSum()).parse());
        //assertEquals(M(105, F("File \"new.gco")).asElements(), new GCoreParser("M105 F\"File \"\"new.gco\" \"Literal\"\"\" ;Comment").parse());
    }
}
