package org.qw3rtrun.p3d.g.code.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.qw3rtrun.p3d.g.code.core.G.*;

class GCoreParserTest {

    @Test
    void parse() {
        assertEquals(M(155).asElements(), new GCoreParser("M155").parse());
        assertEquals(M(105, S(250)).asElements(), new GCoreParser("M105 S250").parse());
        assertEquals(M(105, B(), S(250)).asElements(), new GCoreParser("M105 B S250").parse());
        assertEquals(M(105, F("ile.gco")).asElements(), new GCoreParser("M105 File.gco").parse());
        assertEquals(M(105, COM("Comment"), B(), S(250), F("ile.gco")).asElements(), new GCoreParser("M105 B S250 File.gco ;Comment").parse());

        assertEquals(M(105, LIT("File.gco")).asElements(), new GCoreParser("M105 \"File.gco\"").parse());
        assertEquals(M(105, LIT("File new.gco")).asElements(), new GCoreParser("M105 \"File new.gco\"").parse());
        assertEquals(M(105, LIT("File\"new.gco")).asElements(), new GCoreParser("M105 \"File\"\"new.gco\"").parse());
        //assertEquals(M(105, F("File \"new.gco")).asElements(), new GCoreParser("M105 F\"File \"\"new.gco\" \"Literal\"\"\" ;Comment").parse());
    }
}
