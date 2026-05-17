package org.qw3rtrun.p3d.core.bus;

import lombok.RequiredArgsConstructor;

import java.util.function.Function;
import java.util.function.Predicate;

@RequiredArgsConstructor
public class CommandQueryService<T, R> implements Function<T, R> {

    public CommandQueryService(Class<T> clazz, Function<T, R> function) {
        this.isApplicable = clazz::isInstance;
        this.function = function;
    }

    private final Predicate<Object> isApplicable;
    private final Function<T, R> function;

    public boolean isApplicable(Object t) {
        return isApplicable.test(t);
    }

    @Override
    public R apply(T t) {
        return function.apply(t);
    }
}
