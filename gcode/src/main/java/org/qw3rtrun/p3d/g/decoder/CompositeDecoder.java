package org.qw3rtrun.p3d.g.decoder;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.g.event.GEvent;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class CompositeDecoder implements GEventDecoder<GEvent> {

    private final List<GEventDecoder> encoders;

    @Override
    public Optional<GEvent> decode(String line) {
        return encoders.stream()
                .map(enc -> enc.decode(line))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .map(GEvent.class::cast);
    }

    @Override
    public boolean test(String line) {
        return encoders.stream().anyMatch(enc -> enc.test(line));
    }
}
