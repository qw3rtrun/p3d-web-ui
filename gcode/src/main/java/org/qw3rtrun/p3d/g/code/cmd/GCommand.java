package org.qw3rtrun.p3d.g.code.cmd;

import lombok.NonNull;
import lombok.Value;
import org.qw3rtrun.p3d.g.code.core.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;

@Value
public class GCommand {
    int number;
    GIntField command;
    List<GField> fields;
    List<GLiteral> literals;
    GComment comment;

    public GCommand(int number, @NonNull GIntField command, @NonNull List<GField> fields, @NonNull List<GLiteral> literals, GComment comment) {
        this.number = number;
        this.command = command;
        this.fields = fields;
        this.literals = literals;
        this.comment = comment;
    }

    public GCommand(GIntField command, @NonNull List<GField> fields, @NonNull List<GLiteral> tail, GComment comment) {
        this(-1, command, fields, tail, comment);
    }

    public GCommand(GIntField command) {
        this(-1, command, emptyList(), emptyList(), null);
    }

    public GCommand(GIntField command, List<GField> fields) {
        this(-1, command, fields, emptyList(), null);
    }

    public GCommand(GIntField command, GLiteral string) {
        this(-1, command, emptyList(), List.of(string), null);
    }

    public static GCommand from(@NonNull List<GElement> elements) throws GCodeSyntaxException {
        if (elements.isEmpty()) {
            throw new GCodeSyntaxException(elements.toString(), "GCommand should have at lease one field");
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
                    throw new GCodeSyntaxException(elements.toString(), "GCommand should have int as command field after Nnnn");
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
                        throw new GCodeSyntaxException(elements.toString(), "GField has to be placed before GLiteral");
                    }
                    if (comment != null) {
                        throw new GCodeSyntaxException(elements.toString(), "GField has to be placed before GComment");
                    }
                    fields.add(f);
                }
                if (elem instanceof GString l) {
                    if (comment != null) {
                        throw new GCodeSyntaxException(elements.toString(), "GLiteral has to be placed before GComment");
                    }
                    literals.add(l);
                }
                if (elem instanceof GComment c) {
                    comment = c;
                }
            }
            return new GCommand(number, cmd, fields, literals, comment);
        } else {
            throw new GCodeSyntaxException(elements.toString(), "GCommand should have int as first command field");
        }
    }

    public List<GElement> asElements() {
        var list = new ArrayList<GElement>();
        if (number >= 0) {
            list.add(G.N(number));
        }
        list.add(command);
        list.addAll(fields);
        list.addAll(literals);
        if (comment != null) {
            list.add(comment);
        }
        return list;
    }

    public String name() {
        return command.asString();
    }

    @Override
    public String toString() {
        var pStream =
                Stream.concat(
                        Stream.of(String.valueOf(command.value())),
                        Stream.concat(fields.stream(), literals.stream())
                                .map(Objects::toString)
                );
        if (comment != null) {
            pStream = Stream.concat(pStream, Stream.of(comment.toString()));
        }
        var pStr = pStream.collect(Collectors.joining(", "));
        return STR."\{command.letter()}(\{pStr})";
    }
}
