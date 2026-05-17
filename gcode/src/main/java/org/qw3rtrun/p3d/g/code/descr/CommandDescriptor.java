package org.qw3rtrun.p3d.g.code.descr;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.g.code.cmd.GCommand;
import org.qw3rtrun.p3d.g.code.core.GNamedField;
import org.qw3rtrun.p3d.g.code.decoder.ParameterDecoder;
import org.qw3rtrun.p3d.g.code.decoder.StrValueDecoder;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static java.util.Arrays.stream;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

@RequiredArgsConstructor
public class CommandDescriptor<T extends Record & GEncodable> {

    private final Class<T> type;
    private final GCode commandDescriptor;
    private final Constructor<T> constructor;
    private final ParameterDecoder[] parameterDecoders;
    private final StrValueDecoder valuesDecoders;

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
        var paramsMap = command.getFields().stream()
                .filter(GNamedField.class::isInstance)
                .map(GNamedField.class::cast)
                .collect(toMap(f -> String.valueOf(f.letter()).toUpperCase(), identity()));
        return stream(parameterDecoders).map(pd -> pd.decode(paramsMap)).toArray();
    }

}
