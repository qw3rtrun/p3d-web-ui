package org.qw3rtrun.p3d.g.code.core;

import java.util.*;
import java.util.stream.Collectors;

public record GCommand(GIntField command, GLiteral string, GComment comment, GField... fields) {

    public GCommand(GIntField command) {
        this(command, null, null, new GField[0]);
    }

    public GCommand(GIntField command, GField... fields) {
        this(command, null, null, fields);
    }

    public GCommand(GIntField command, GLiteral string) {
        this(command, string, null);
    }

    public Collection<GElement> asElements() {
        var list = new ArrayList<GElement>();
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

    @Override
    public String toString() {
        var fstr = (fields == null)? "" : " "+Arrays.stream(fields).map(Objects::toString).collect(Collectors.joining(" "));
        return STR."\{command}\{fstr}\{string == null? "": " "+string}\{comment == null? "": " "+comment}";
    }
}
