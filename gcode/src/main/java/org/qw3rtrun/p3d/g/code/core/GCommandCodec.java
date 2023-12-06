package org.qw3rtrun.p3d.g.code.core;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@RequiredArgsConstructor
public class GCommandCodec {

    private final GCoreCodec codec;

    public String encode(GCommand cmd) {
        return codec.encode(cmd.asElements());
    }

    public GCommand decode(String line) throws GCodeSyntaxException {
        var elements = codec.decode(line);
        if (elements.isEmpty()) {
            throw new GCodeSyntaxException(line, "GCommand should have at lease one field");
        }
        Iterator<? extends GElement> iterator = elements.iterator();
        if (iterator.next() instanceof GIntField cmd) {
            List<GField> fields = new ArrayList<>();
            GComment comment = null;
            GLiteral literal = null;
            while (iterator.hasNext()) {
                var elem = iterator.next();
                if (elem instanceof GField f) {
                    if (literal != null) {
                        throw new GCodeSyntaxException(line, "GField has to be placed before GLiteral");
                    }
                    if (comment != null) {
                        throw new GCodeSyntaxException(line, "GField has to be placed before GComment");
                    }
                    fields.add(f);
                }
                if (elem instanceof GLiteral l) {
                    if (comment != null) {
                        throw new GCodeSyntaxException(line, "GLiteral has to be placed before GComment");
                    }
                    literal = l;
                }
                if (elem instanceof GComment c) {
                    comment = c;
                }
            }
            return new GCommand(cmd, literal, comment, fields.toArray(new GField[0]));
        } else {
            throw new GCodeSyntaxException(line, "GCommand should have int as first command field");
        }
    }
}
