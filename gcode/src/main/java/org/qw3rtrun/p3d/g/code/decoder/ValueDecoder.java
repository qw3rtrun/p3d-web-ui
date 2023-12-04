package org.qw3rtrun.p3d.g.code.decoder;

import org.apache.commons.lang3.EnumUtils;
import org.qw3rtrun.p3d.g.code.GenericGParameter;

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

    T extractValue(GenericGParameter parameter);
}

final class BooleanDecoder implements ValueDecoder<Boolean> {
    @Override
    public Boolean extractValue(GenericGParameter parameter) {
        return switch (parameter) {
            case GenericGParameter p when p.bool() != null -> p.bool();
            case GenericGParameter p when p.integer() != null -> p.integer() > 0;
            case GenericGParameter p when p.number() != null -> p.number() > 0;
            case GenericGParameter p when p.str() != null -> p.str().equalsIgnoreCase("true");
            default -> null;
        };
    }
}

final class IntDecoder implements ValueDecoder<Integer> {
    @Override
    public Integer extractValue(GenericGParameter parameter) {
        return switch (parameter) {
            case GenericGParameter p when p.integer() != null -> p.integer();
            case GenericGParameter p when p.number() != null -> p.number().intValue();
            default -> null;
        };
    }
}

final class DoubleDecoder implements ValueDecoder<Double> {
    @Override
    public Double extractValue(GenericGParameter parameter) {
        return switch (parameter) {
            case GenericGParameter p when p.number() != null -> p.number();
            case GenericGParameter p when p.integer() != null -> p.integer().doubleValue();
            default -> null;
        };
    }
}

final class StringDecoder implements ValueDecoder<String> {
    @Override
    public String extractValue(GenericGParameter parameter) {
        return parameter.str();
    }
}

final class EnumDecoder<E extends Enum<E>> implements ValueDecoder<E> {

    private final List<E> enumList;

    public EnumDecoder(Class<E> enumType) {
        this.enumList = EnumUtils.getEnumList(enumType);
    }

    @Override
    public E extractValue(GenericGParameter parameter) {
        if (parameter.integer() == null || parameter.integer() >= enumList.size()) {
            return null;
        }
        return enumList.get(parameter.integer());
    }
}
