package org.qw3rtrun.p3d.g.code.core;

import org.qw3rtrun.p3d.g.code.cmd.GCommand;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GAwareDecoder {
    final private Map<Character, Map<Integer, GDescriptor>> descrs;

    public GAwareDecoder(List<GDescriptor> descrs) {
        this.descrs = descrs.stream().collect(Collectors.groupingBy(
                GDescriptor::getLetter,
                Collectors.toMap(GDescriptor::getNumber, Function.identity())
        ));

    }

    public Optional<GCommand> decode(String line) {
        if (line.length() < 2) {
            return Optional.empty();
        }
        var parser = new Parser(line);
        parser.skipSpaces();
        char letter = parser.nextLetter();
        int number = parser.nextNumber();
        descrs.getOrDefault(letter, Collections.emptyMap());
        return Optional.empty();
    }
}

class Parser {
    private final String line;
    private final char[] chars;
    int pointer = 0;
    int start = 0;

    Parser(String line) {
        this.line = line;
        this.chars = line.toCharArray();
    }

    int skipSpaces() {
        start = pointer;
        while (chars[pointer] == ' ') {
            pointer++;
        }
        return pointer - start;
    }

    char nextLetter() {
        char c = chars[pointer++];
        start = pointer;
        if (!Character.isAlphabetic(c)) {
            throw new IllegalArgumentException(c + " is not a letter");
        }
        return c;
    }

    int nextNumber() {
        start = pointer;
        while (chars[pointer] == '-') {
            pointer++;
        }
        return pointer - start;
    }

}
