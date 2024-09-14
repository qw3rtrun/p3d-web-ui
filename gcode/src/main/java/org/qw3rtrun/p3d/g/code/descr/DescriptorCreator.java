package org.qw3rtrun.p3d.g.code.descr;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.g.code.decoder.ParameterDecoder;
import org.qw3rtrun.p3d.g.code.decoder.ValueDecoder;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Optional;

import static java.util.Arrays.stream;

@RequiredArgsConstructor
public class DescriptorCreator {

    public <T extends Record & GEncodable> CommandDescriptor<T> build(Class<T> type, GCode annotation) {
        return findParametrizedConstructor(type)
                .map(c -> new CommandDescriptor(type, annotation, c, buildParamDecoders(type), null))
                .or(() -> findRawConstructor(type).or(() -> findDefaultConstructor(type))
                        .map(c -> new CommandDescriptor<>(type, annotation, c, null, null))
                ).orElseThrow(() -> new IllegalArgumentException(
                        STR."@GCommand annotated class should have 'default' or String constructor or GParameter annotated constructor params: \{type}"));
    }

    private <T extends GEncodable> ParameterDecoder[] buildParamDecoders(Class<T> type) {
        return stream(type.getRecordComponents())
                .map(this::buildParamDecoder).toArray(ParameterDecoder[]::new);
    }

    private <T extends GEncodable> ParameterDecoder buildParamDecoder(RecordComponent rc) {
        var pDescr = rc.getAnnotation(GParam.class);
        if (pDescr == null) {
            throw new IllegalArgumentException("GCommand constructor's params should be annotated with GParameter");
        }
        return new ParameterDecoder(pDescr, ValueDecoder.build(rc.getType()));
    }

    private <T> Optional<Constructor<T>> findParametrizedConstructor(Class<T> type) {
        var gAnnotationCount = stream(type.getRecordComponents()).map(rc -> rc.getAnnotation(GParam.class)).count();
        if (gAnnotationCount == type.getRecordComponents().length) {
            var paramTypes = stream(type.getRecordComponents()).map(RecordComponent::getType).toArray(Class[]::new);
            try {
                return Optional.of(type.getConstructor(paramTypes));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private <T> Optional<Constructor<T>> findDefaultConstructor(Class<T> type) {
        try {
            return Optional.of(type.getConstructor());
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        }
    }

    private <T> Optional<Constructor<T>> findRawConstructor(Class<T> type) {
        try {
            var c = type.getConstructor(String.class);
            if (c.getParameters()[0].getAnnotation(GParam.class) != null) {
                return Optional.of(c);
            } else {
                return Optional.empty();
            }
        } catch (NoSuchMethodException ex) {
            return Optional.empty();
        }
    }
}
