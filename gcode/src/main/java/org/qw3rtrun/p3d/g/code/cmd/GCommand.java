package org.qw3rtrun.p3d.g.code.cmd;

import lombok.NonNull;
import lombok.Value;
import org.qw3rtrun.p3d.g.code.core.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
        var fstr = (fields.isEmpty()) ? "" : STR." \{fields.stream().map(Objects::toString).collect(Collectors.joining(" "))}";
        var tstr = (literals.isEmpty()) ? "" : STR." \{literals.stream().map(Objects::toString).collect(Collectors.joining(" "))}";
        var cstr = comment == null ? "" : STR." \{comment}";
        return STR."\{command.letter()}\{command.value()}(\{fstr}\{tstr}\{cstr})";
    }
}
