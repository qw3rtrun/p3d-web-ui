package org.qw3rtrun.p3d.g;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GTest {

    private G g;
    private StringWriter output;

    @BeforeEach
    void setUp() {
        output = new StringWriter();
        g = new G(output::append);
    }

    @Test
    void m105() {
        g.m105();
        assertEquals("M105 T0", output.toString());
    }

    @Test
    void tempReport() {
        g.tempReport();
        assertEquals("M105 T0", output.toString());
    }

    @Test
    void m105T1() {
        g.m105(1);
        assertEquals("M105 T1", output.toString());
    }

    @Test
    void temp1Report() {
        g.tempReport(1);
        assertEquals("M105 T1", output.toString());
    }
}
