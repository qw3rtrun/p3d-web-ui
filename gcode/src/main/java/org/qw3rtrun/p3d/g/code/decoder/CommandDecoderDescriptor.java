package org.qw3rtrun.p3d.g.code.decoder;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.g.code.GCode;
import org.qw3rtrun.p3d.g.code.GEncodable;
import org.qw3rtrun.p3d.g.code.GParam;
import org.qw3rtrun.p3d.g.code.core.GCommand;
import org.qw3rtrun.p3d.g.code.core.GCoreCodec;
import org.qw3rtrun.p3d.g.code.core.GField;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Map;

import static java.util.Arrays.stream;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

@RequiredArgsConstructor
public class CommandDecoderDescriptor<T extends GEncodable> {

    private final Class<T> type;
    private final GCode commandDescriptor;
    private final Constructor<T> constructor;
    private final ParameterDecoder[] parameterDecoders;

    public static <T extends GEncodable> CommandDecoderDescriptor<T> build(Class<T> type, GCode annotation) {
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

    private static <T extends GEncodable> ParameterDecoder[] buildParamDecoders(Class<T> type) {
        return stream(type.getRecordComponents())
                .map(CommandDecoderDescriptor::buildParamDecoder).toArray(ParameterDecoder[]::new);
    }

    private static <T extends GEncodable> ParameterDecoder buildParamDecoder(RecordComponent rc) {
        var pDescr = rc.getAnnotation(GParam.class);
        if (pDescr == null) {
            throw new IllegalArgumentException("GCommand constructor's params should be annotated with GParameter");
        }
        return new ParameterDecoder(pDescr, ValueDecoder.build(rc.getType()));
    }

    private static <T> Constructor<T> findParametrizedConstructor(Class<T> type) {
        var gAnnotationCount = stream(type.getRecordComponents()).map(rc -> rc.getAnnotation(GParam.class)).count();
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
            if (c.getParameters()[0].getAnnotation(GParam.class) != null) {
                return c;
            } else {
                return null;
            }
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    public boolean isApplicable(GCommand gcode) {
        return commandDescriptor.value().equalsIgnoreCase(gcode.name());
    }

    public T decode(GCommand gCode) {
        try {
            return type.cast(constructor.newInstance(buildParameters(gCode)));
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private Object[] buildParameters(GCommand command) {
        var paramsMap = stream(command.fields()).collect(toMap(f -> String.valueOf(f.letter()).toUpperCase(), identity()));
        return stream(parameterDecoders).map(pd -> pd.decode(paramsMap)).toArray();
    }

    public static class ParameterDecoder {

        private final GCoreCodec codec = new GCoreCodec();
        private final GParam descr;
        private final ValueDecoder valueDecoder;
        private final GField defaultParam;

        public ParameterDecoder(GParam descr, ValueDecoder valueDecoder) {
            this.descr = descr;
            this.valueDecoder = valueDecoder;
            if (!descr.defaultValue().isEmpty()) {
                defaultParam = (GField) codec.decode(descr.value() + descr.defaultValue()).get(0);
            } else {
                defaultParam = null;
            }
        }

        public Object decode(Map<String, GField> paramsMap) {
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
