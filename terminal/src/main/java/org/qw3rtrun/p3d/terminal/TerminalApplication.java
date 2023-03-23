package org.qw3rtrun.p3d.terminal;

import java.io.IOException;

public class TerminalApplication {

    public static void main(String[] args) throws IOException {

        GFlux gflux = new GFlux();

        Terminal terminal = new Terminal();
        terminal.connect();

        terminal.inbound()
                .map(s -> "| " + s)
                .subscribe(System.out::print);

        terminal.outbound(gflux.getFlux());

        var g = gflux.getG();
        g.m105();

        while (true) {
            Thread.yield();
        }
    }
}
