package org.qw3rtrun.p3d.g.code.decoder;

import org.apache.commons.lang3.EnumUtils;
import org.qw3rtrun.p3d.g.code.core.*;

import java.util.List;

public sealed interface ValueDecoder<T> {

    static ValueDecoder<?> build(Class<?> type) {
        return switch (type) {
            case Class<?> i when int.class.isAssignableFrom(i) -> new IntDecoder();
            case Class<?> i when Integer.class.isAssignableFrom(i) -> new IntDecoder();
            case Class<?> i when long.class.isAssignableFrom(i) -> new IntDecoder();
            case Class<?> i when Long.class.isAssignableFrom(i) -> new IntDecoder();
            case Class<?> i when double.class.isAssignableFrom(i) -> new DoubleDecoder();
            case Class<?> i when Double.class.isAssignableFrom(i) -> new DoubleDecoder();
            case Class<?> i when String.class.isAssignableFrom(i) -> new StringDecoder();
            case Class<?> i when boolean.class.isAssignableFrom(i) -> new BooleanDecoder();
            case Class<?> i when Boolean.class.isAssignableFrom(i) -> new BooleanDecoder();
            case Class<?> i when i.isEnum() -> new EnumDecoder<>((Class<Enum>) i);
            default -> throw new IllegalArgumentException("Parameter type " + type + " is not supported");
        };
    }

    T extractValue(GField parameter);
}

final class BooleanDecoder implements ValueDecoder<Boolean> {
    @Override
    public Boolean extractValue(GField parameter) {
        return switch (parameter) {
            case GFlagField p -> true;
            case GIntField i -> (i.value() > 0);
            case GStrField s -> s.value().equalsIgnoreCase("true");
            default -> null;
        };
    }
}

final class IntDecoder implements ValueDecoder<Integer> {
    @Override
    public Integer extractValue(GField parameter) {
        return switch (parameter) {
            case GIntField i -> i.value();
            case GDoubleField d -> d.value().intValue();
            default -> null;
        };
    }
}

final class DoubleDecoder implements ValueDecoder<Double> {
    @Override
    public Double extractValue(GField parameter) {
        return switch (parameter) {
            case GIntField i -> (double) i.value();
            case GDoubleField d -> d.value().doubleValue();
            default -> null;
        };
    }
}

final class StringDecoder implements ValueDecoder<String> {
    @Override
    public String extractValue(GField parameter) {
        return parameter.rawValue();
    }
}

final class EnumDecoder<E extends Enum<E>> implements ValueDecoder<E> {

    private final List<E> enumList;

    public EnumDecoder(Class<E> enumType) {
        this.enumList = EnumUtils.getEnumList(enumType);
    }

    @Override
    public E extractValue(GField parameter) {
        if (parameter instanceof GIntField i) {
            return enumList.get(i.value());
        }
        return null;
    }
}
