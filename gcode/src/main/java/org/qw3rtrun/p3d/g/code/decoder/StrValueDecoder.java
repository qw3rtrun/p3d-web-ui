package org.qw3rtrun.p3d.g.code.decoder;

import org.qw3rtrun.p3d.g.code.core.GStrField;
import org.qw3rtrun.p3d.g.code.descr.GStrValue;

public class StrValueDecoder {

    private final GStrValue descr;
    private final ValueDecoder valueDecoder = new StringDecoder();
    private final GStrField defaultValue;

    public StrValueDecoder(GStrValue descr, GStrField valueDecoder) {
        this.descr = descr;
        this.defaultValue = valueDecoder;
    }

    public Object decode() {
//        var gParam = paramsMap.get(descr.value().toUpperCase());
//        if (gParam == null) {
//            if (defaultValue != null) {
//                return valueDecoder.extractValue(defaultValue);
//            } else {
//                return null;
//            }
//        } else {
//            return valueDecoder.extractValue(gParam);
//        }
        return null;
    }
}
