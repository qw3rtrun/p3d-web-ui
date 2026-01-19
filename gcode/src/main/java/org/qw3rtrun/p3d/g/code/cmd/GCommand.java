package org.qw3rtrun.p3d.g.code.cmd;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Value;
import org.qw3rtrun.p3d.g.code.core.GCodeSyntaxException;
import org.qw3rtrun.p3d.g.code.core.GComment;
import org.qw3rtrun.p3d.g.code.core.GField;
import org.qw3rtrun.p3d.g.code.core.GIntField;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;

@Value
@AllArgsConstructor
public class GCommand {
    GIntField command;
    List<GField> fields;

    public GCommand(GIntField command) {
        this(command, emptyList());
    }

    public GCommand(GIntField command, GField... field) {
        this(command, Arrays.stream(field).toList());
    }

    public static GCommand from(@NonNull List<GField> elements) throws GCodeSyntaxException {
        if (elements.isEmpty()) {
            throw new GCodeSyntaxException(elements.toString(), "GCommand should have at lease one element");
        }
        if (elements.getFirst() instanceof GIntField first) {
            return new GCommand(first, elements.subList(1, elements.size()));
        } else {
            throw new GCodeSyntaxException(elements.toString(), "GCommand should have int as first command field");
        }
    }

    public List<GField> asFields() {
        var result = new ArrayList<>(fields);
        result.addFirst(command);
        return result;
    }

    public String name() {
        return command.asString();
    }

    public GLine asLine() {
        return new GLine(this);
    }

    public GLine withComment(String comment) {
        return new GLine(this, new GComment(comment));
    }

    @Override
    public String toString() {
        var pStream =
                Stream.concat(
                        Stream.of(String.valueOf(command.value())),
                        fields.stream()
                                .map(Objects::toString)
                );
        var pStr = pStream.collect(Collectors.joining(", "));
        return command.letter() + "(" + pStr + ")";
    }
}
