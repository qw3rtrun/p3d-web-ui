package org.qw3rtrun.p3d.g.code.cmd;

import lombok.NonNull;
import lombok.Value;
import org.qw3rtrun.p3d.g.code.core.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

@Value
public class GLine {
    GIntField number;
    GCommand cmd;
    GIntField checksum;
    GComment comment;

    public GLine(GIntField number, GCommand cmd, GIntField checksum, GComment comment) {
        this.number = number;
        this.cmd = cmd;
        this.checksum = checksum;
        this.comment = comment;
    }

    public GLine(GCommand cmd) {
        this(null, cmd, null, null);
    }

    public GLine(GCommand cmd, GComment comment) {
        this(null, cmd, null, comment);
    }

    public static GLine from(@NonNull List<GElement> elements) throws GCodeSyntaxException {
        if (elements.isEmpty()) {
            throw new GCodeSyntaxException(elements.toString(), "GCommand should have at lease one element");
        }
        Iterator<? extends GElement> iterator = elements.iterator();
        if (iterator.next() instanceof GIntField first) {
            GIntField cmd;
            GIntField number = null;
            if (first.isN()) {
                number = first;
                if (iterator.hasNext() && iterator.next() instanceof GIntField c) {
                    cmd = c;
                } else {
                    throw new GCodeSyntaxException(elements.toString(), "GCommand should have int as command field after Nnnn");
                }
            } else {
                cmd = first;
            }
            List<GField> fields = new ArrayList<>();
            GComment comment = null;
            while (iterator.hasNext()) {
                var elem = iterator.next();
                if (elem instanceof GField f) {
                    if (comment != null) {
                        throw new GCodeSyntaxException(elements.toString(), "GField has to be placed before GComment");
                    }
                    fields.add(f);
                }
                if (elem instanceof GComment c) {
                    comment = c;
                }
            }
            GIntField checksum = (!fields.isEmpty() && fields.getLast() instanceof GIntField i && i.isG()) ? i : null;
            fields = checksum == null ? fields : fields.subList(0, fields.size() - 1);

            return new GLine(number, new GCommand(cmd, fields), checksum, comment);
        } else {
            throw new GCodeSyntaxException(elements.toString(), "GCommand should have int as first command field");
        }
    }

    public List<GElement> asElements() {
        var list = new ArrayList<GElement>();
        if (number != null) {
            list.add(number);
        }
        list.addAll(cmd.asFields());
        if (comment != null) {
            list.add(comment);
        }
        return list;
    }

    @Override
    public String toString() {
        return asElements().stream().map(Object::toString).collect(Collectors.joining(", "));
    }
}
