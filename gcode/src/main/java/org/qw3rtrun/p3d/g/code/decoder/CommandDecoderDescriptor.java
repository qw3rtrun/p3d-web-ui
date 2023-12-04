package org.qw3rtrun.p3d.g.code.decoder;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.g.code.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Arrays.stream;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

@RequiredArgsConstructor
public class CommandDecoderDescriptor<T extends GCode> {

    private final Class<T> type;
    private final GCommand commandDescriptor;
    private final Constructor<T> constructor;
    private final ParameterDecoder[] parameterDecoders;

    public static <T extends GCode> CommandDecoderDescriptor<T> build(Class<T> type, GCommand annotation) {
        var c = findParametrizedConstructor(type);
        if (c != null) {
            var paramDecoders = buildParamDecoders(type);
            return new CommandDecoderDescriptor<>(type, annotation, c, paramDecoders);
        } else {
            c = findRawConstructor(type);
            if (c == null) {
                c = findDefaultConstructor(type);
                if (c == null) {
                    throw new IllegalArgumentException("GCommand annotated class should have 'default' or String constructor or GParameter annotated constructor params: " + type);
                }
            }
            return new CommandDecoderDescriptor<>(type, annotation, c, null);
        }
    }

    private static <T extends GCode> ParameterDecoder[] buildParamDecoders(Class<T> type) {
        return stream(type.getRecordComponents())
                .map(CommandDecoderDescriptor::buildParamDecoder).toArray(ParameterDecoder[]::new);
    }

    private static <T extends GCode> ParameterDecoder buildParamDecoder(RecordComponent rc) {
        var pDescr = rc.getAnnotation(GParameter.class);
        if (pDescr == null) {
            throw new IllegalArgumentException("GCommand constructor's params should be annotated with GParameter");
        }
        return new ParameterDecoder(pDescr, ValueDecoder.build(rc.getType()));
    }

    private static <T> Constructor<T> findParametrizedConstructor(Class<T> type) {
        var gAnnotationCount = stream(type.getRecordComponents()).map(rc -> rc.getAnnotation(GParameter.class)).count();
        if (gAnnotationCount == type.getRecordComponents().length) {
            var paramTypes = stream(type.getRecordComponents()).map(RecordComponent::getType).toArray(Class[]::new);
            try {
                return type.getConstructor(paramTypes);
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
        return null;
    }

    private static <T> Constructor<T> findDefaultConstructor(Class<T> type) {
        return (Constructor<T>) stream(type.getConstructors()).filter(
                c -> c.getParameters().length == 0
        ).findFirst().orElse(null);
    }

    private static <T> Constructor<T> findRawConstructor(Class<T> type) {
        try {
            var c = type.getConstructor(String.class);
            if (c.getParameters()[0].getAnnotation(GParameter.class) != null) {
                return c;
            } else {
                return null;
            }
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    public boolean isApplicable(GenericGCode gcode) {
        return commandDescriptor.value().equalsIgnoreCase(gcode.prefix());
    }

    public T decode(GenericGCode gCode) {
        try {
            if (parameterDecoders == null) {
                return type.cast(constructor.newInstance(gCode.rawParams()));
            }
            return type.cast(constructor.newInstance(buildParameters(gCode.parameters())));
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private Object[] buildParameters(GenericGParameter[] parameters) {
        var paramsMap = stream(parameters).collect(toMap(GenericGParameter::name, identity()));
        return stream(parameterDecoders).map(pd -> pd.decode(paramsMap)).toArray();
    }

    public static class ParameterDecoder {
        private final GParameter descr;
        private final ValueDecoder valueDecoder;
        private final GenericGParameter defaultParam;

        public ParameterDecoder(GParameter descr, ValueDecoder valueDecoder) {
            this.descr = descr;
            this.valueDecoder = valueDecoder;
            if (!descr.defaultValue().isEmpty()) {
                defaultParam = GenericGParameter.parse(descr.value() + descr.defaultValue());
            } else {
                defaultParam = null;
            }
        }

        public Object decode(Map<String, GenericGParameter> paramsMap) {
            var gParam = paramsMap.get(descr.value().toUpperCase());
            if (gParam == null) {
                if (defaultParam != null) {
                    return valueDecoder.extractValue(defaultParam);
                } else {
                    return null;
                }
            } else {
                return valueDecoder.extractValue(gParam);
            }
        }
    }
}
