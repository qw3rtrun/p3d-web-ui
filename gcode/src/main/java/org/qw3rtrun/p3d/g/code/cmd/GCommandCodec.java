package org.qw3rtrun.p3d.g.code.cmd;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.g.code.core.*;

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
        if (iterator.next() instanceof GIntField first) {
            GIntField cmd;
            int number = -1;
            if (first.isN()) {
                number = first.value();
                if (iterator.hasNext() && iterator.next() instanceof GIntField c) {
                    cmd = c;
                } else {
                    throw new GCodeSyntaxException(line, "GCommand should have int as command field after Nnnn");
                }
            } else {
                cmd = first;
            }
            List<GField> fields = new ArrayList<>();
            GComment comment = null;
            List<GLiteral> literals = new ArrayList<>();
            while (iterator.hasNext()) {
                var elem = iterator.next();
                if (elem instanceof GField f) {
                    if (!literals.isEmpty()) {
                        throw new GCodeSyntaxException(line, "GField has to be placed before GLiteral");
                    }
                    if (comment != null) {
                        throw new GCodeSyntaxException(line, "GField has to be placed before GComment");
                    }
                    fields.add(f);
                }
                if (elem instanceof GString l) {
                    if (comment != null) {
                        throw new GCodeSyntaxException(line, "GLiteral has to be placed before GComment");
                    }
                    literals.add(l);
                }
                if (elem instanceof GComment c) {
                    comment = c;
                }
            }
            return new GCommand(number, cmd, fields, literals, comment);
        } else {
            throw new GCodeSyntaxException(line, "GCommand should have int as first command field");
        }
    }
}
