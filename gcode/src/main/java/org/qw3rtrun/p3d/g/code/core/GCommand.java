package org.qw3rtrun.p3d.g.code.core;

import lombok.Builder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record GCommand(int number, GIntField command, GLiteral string, GComment comment, GField... fields) {

    public GCommand(GIntField command, GLiteral string, GComment comment, GField... fields) {
        this(-1, command, string, comment, fields);
    }

    public GCommand(GIntField command) {
        this(-1, command, null, null);
    }

    public GCommand(GIntField command, GField... fields) {
        this(-1, command, null, null, fields);
    }

    public GCommand(GIntField command, GLiteral string) {
        this(-1, command, string, null);
    }

    public List<GElement> asElements() {
        var list = new ArrayList<GElement>();
        if (number >= 0) {
            list.add(G.N(number));
        }
        list.add(command);
        if (fields != null) {
            list.addAll(List.of(fields));
        }
        if (string != null) {
            list.add(string);
        }
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
        var fstr = (fields == null) ? "" : " " + Arrays.stream(fields).map(Objects::toString).collect(Collectors.joining(" "));
        return STR. "\{ command.letter() }\{ command.value() }(\{ fstr }\{ string == null ? "" : " " + string }\{ comment == null ? "" : " " + comment })" ;
    }
}
