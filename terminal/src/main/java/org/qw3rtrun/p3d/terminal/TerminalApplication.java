package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.code.FirmwareInfo;
import org.qw3rtrun.p3d.g.code.GCode;
import org.qw3rtrun.p3d.g.code.ReportHotendTemperature;
import reactor.core.publisher.Flux;
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

//        var sendMono = terminal.connection.outbound()
//                .sendString(Mono.just("M105\n").publishOn(executor).log())
//                .then();


//        var receiveMono = terminal.inbound()
//                .doOnNext(s -> {
//                    System.out.println("-> " + s);
//                })
//                .subscribe();

        var send1Mono = terminal.send(
                Flux.<GCode>generate(s -> s.next(new ReportHotendTemperature()))
                        .take(5)
                        .log()
        );

        var send2Mono = terminal.send(
                Flux.<GCode>generate(s -> s.next(new FirmwareInfo()))
                        .take(5)
                        .log()
        );

        var send3Mono = terminal.connection.outbound()
                        .sendString(Flux.<String>generate(s -> s.next("M105\n"))
                                .take(5)
                                .log())
                .then();
        var send4Mono = terminal.connection.outbound()
                .sendString(Flux.<String>generate(s -> s.next("M155\n"))
                        .take(5)
                        .log())
                .then();


        System.out.println("Done " + terminal.connection);

        send3Mono.subscribe();
        send4Mono.subscribe();
        //Mono.zip(send3Mono, send4Mono).block();

        System.out.println("DisposeNow " + terminal.connection);
        terminal.connection.onDispose().block();
    }

    private static void onConnect(Connection c) {
        System.out.println("onConnect");
        //terminal.send(new ReportHotendTemperature()).subscribe();
        System.out.println("onConnect DONE");
    }
}
