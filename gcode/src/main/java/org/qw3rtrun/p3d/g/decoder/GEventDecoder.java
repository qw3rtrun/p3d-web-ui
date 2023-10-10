package org.qw3rtrun.p3d.g.decoder;


import org.qw3rtrun.p3d.core.msg.GEvent;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

public interface GEventDecoder<G extends GEvent> extends Function<String, Optional<G>>, Predicate<String> {

    Optional<G> decode(String line);

    @Override
    default Optional<G> apply(String s) {
        return decode(s);
    }

    @Override
    default boolean test(String s) {
        return decode(s).isPresent();
    }
}
