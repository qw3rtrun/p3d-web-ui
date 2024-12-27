package org.qw3rtrun.p3d.core.bus;

import java.util.Arrays;
import java.util.List;

public class CommandQueryBus implements CommandQueryGateway {

    private final List<CommandQueryService> services;

    public CommandQueryBus(List<CommandQueryService<?, ?>> services) {
        this.services = Arrays.stream(services.toArray(new CommandQueryService[]{})).toList();
    }

    public <R> R send(Object req, Class<R> responseType) {
        return send(req);
    }

    @SuppressWarnings("unchecked")
    public <R> R send(Object req) {
        return (R) services.stream()
                .filter(s -> s.isApplicable(req))
                .findFirst()
                .map(s -> s.apply(req))
                .orElseThrow(() -> new UnsupportedOperationException("CommandQueryBus doesn't support " + req));
    }
}
