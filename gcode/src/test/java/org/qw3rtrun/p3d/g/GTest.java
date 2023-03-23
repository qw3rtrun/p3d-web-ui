package org.qw3rtrun.p3d.g;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.G;

import java.io.IOException;
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
    void m105() throws IOException {
        g.m105();
        assertEquals("M105 T0\n", output.toString());
    }

    @Test
    void tempReport() throws IOException {
        g.tempReport();
        assertEquals("M105 T0\n", output.toString());
    }

    @Test
    void m105T1() throws IOException {
        g.m105(1);
        assertEquals("M105 T1\n", output.toString());
    }

    @Test
    void temp1Report() throws IOException {
        g.tempReport(1);
        assertEquals("M105 T1\n", output.toString());
    }
}
