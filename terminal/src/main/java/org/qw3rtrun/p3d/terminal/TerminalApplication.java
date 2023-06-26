package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.code.GCode;
import org.qw3rtrun.p3d.g.code.ReportHotendTemperature;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;

import java.io.IOException;

public class TerminalApplication {

    private static final Terminal terminal = new Terminal();

    private static final Scheduler executor = Schedulers.single();

    public static void main(String[] args) throws IOException {

        terminal.connect();

        var sendMono = terminal.connection.outbound()
                .sendString(Mono.just("M105\n").publishOn(executor).log())
                .then();

        var receiveMono = terminal.connection.inbound().receive()
                .asString()
                .publishOn(executor);
        receiveMono.log().subscribe(s -> {
            System.out.println("-> " + s);
            terminal.connection.dispose();
        });

        System.out.println("Sent");
        sendMono.log().subscribe();

        System.out.println("Done "+ terminal.connection);

        terminal.connection.onDispose().block();
    }

    private static void onConnect(Connection c) {
        System.out.println("onConnect");
        //terminal.send(new ReportHotendTemperature()).subscribe();
        System.out.println("onConnect DONE");
    }
}
