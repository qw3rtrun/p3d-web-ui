package org.qw3rtrun.p3d.g.code.decoder;

import org.qw3rtrun.p3d.g.code.core.GNamedField;
import org.qw3rtrun.p3d.g.code.descr.GParam;

import java.util.Map;

public class ParameterDecoder {

    private final GParam descr;
    private final ValueDecoder valueDecoder;
    private final GNamedField defaultParam;

    public ParameterDecoder(GParam descr, ValueDecoder valueDecoder) {
        this.descr = descr;
        this.valueDecoder = valueDecoder;
        if (!descr.defaultValue().isEmpty()) {
            defaultParam = GNamedField.from(descr.value() + descr.defaultValue());
        } else {
            defaultParam = null;
        }
    }

    public Object decode(Map<String, GNamedField> paramsMap) {
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
