package org.qw3rtrun.p3d.g.code.core;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class GCoreParser {
    private final String line;
    private final char[] chars;

    int pointer = 0;
    int checksum = 0;

    int start = 0;

    List<GElement> elements = new ArrayList<>();

    public GCoreParser(String line) {
        this.line = line;
        this.chars = line.toCharArray();
    }

    public List<GElement> parse() throws GCodeSyntaxException {

        while (state != EOL) {
            state.process();
        }
        return elements;
    }

    private GElement decodeElement(String raw) {
        return switch (raw.substring(1)) {
            case String val when raw.startsWith("\"") -> new GLiteral(decodeString(raw));
            case String val when val.isEmpty() -> new GFlagField(raw.charAt(0));
            case String val when !NumberUtils.isCreatable(val) -> new GStrField(raw.charAt(0), decodeString(val));
            case String val -> switch (NumberUtils.createNumber(val)) {
                case Integer i -> new GIntField(raw.charAt(0), i);
                case BigDecimal bd -> new GDoubleField(raw.charAt(0), bd);
                case Number n -> new GDoubleField(raw.charAt(0), BigDecimal.valueOf(n.doubleValue()));
            };
        };
    }

    private GComment decodeComment(String raw) {
        return new GComment(raw.substring(1));
    }

    private int decodeChecksum(String str) {
        try {
            return Integer.parseInt(str.substring(1));
        } catch (NumberFormatException e) {
            throw new GCodeSyntaxException(str, "Checksum should be a number");
        }
    }

    private String decodeString(String str) {
        if (str.startsWith("\"")) {
            if (str.length() < 2 || str.charAt(str.length() - 1) != '"') {
                throw new GCodeSyntaxException(str, "Quoted String's end-quote not found");
            }
        }
        if (str.startsWith("\"") && str.length() > 1 && str.charAt(str.length() - 1) == '"') {
            return StringUtils.replace(str.substring(1, str.length() - 1), "\"\"", "\"");
        }
        return str;
    }

    private abstract class State {
        abstract void next() throws GCodeSyntaxException;

        void endAndThen(State next) {
            start(next);
        }

        void start(State next) {
            start = pointer;
            state = next;
        }

        void process() throws GCodeSyntaxException {
            if (pointer >= chars.length) {
                endAndThen(EOL);
            } else {
                next();
            }
        }
    }

    private final State DELIM = new State() {
        @Override
        public void next() {
            var current = chars[pointer];
            if (current == ' ') {
                checksum ^= current;
                pointer++;
            } else {
                start(ELEMENT);
            }
        }
    };

    private final State ELEMENT = new State() {
        @Override
        public void next() {
            var current = chars[pointer];
            if (pointer == start) {
                switch (current) {
                    case '"' -> start(LITERAL);
                    case ';' -> start(COMMENT);
                    case '*' -> start(CHECKSUM);
                    default -> {
                        checksum ^= current;
                        pointer++;
                    }
                }
            } else {
                switch (current) {
                    case '"' -> {
                        checksum ^= current;
                        pointer++;
                        state = LITERAL;
                    }
                    case '*' -> endAndThen(CHECKSUM);
                    case ' ' -> endAndThen(DELIM);
                    case ';' -> endAndThen(COMMENT);
                    default -> {
                        checksum ^= current;
                        pointer++;
                    }
                }
            }
        }

        void endAndThen(State next) {
            elements.add(decodeElement(new String(chars, start, pointer - start)));
            super.endAndThen(next);
        }
    };

    private final State LITERAL = new State() {
        @Override
        void next() throws GCodeSyntaxException {
            var current = chars[pointer];
            checksum ^= current;
            if (start == pointer) {
                pointer++;
            } else {
                if (!(chars[pointer] == '"' && (pointer == chars.length - 1 || chars[pointer + 1] != '"')) ||
                        !(chars[pointer] == '"' && chars[pointer - 1] != '"')) {
                    pointer++;
                } else {
                    pointer++;
                    endAndThen(DELIM);
                }
            }
        }

        @Override
        void endAndThen(State next) {
            var s = decodeElement(new String(chars, start, pointer - start));
            elements.add(s);
            super.endAndThen(next);
        }
    };

    private final State CHECKSUM = new State() {
        @Override
        public void next() {
            if (start == pointer) {
                pointer++;
            } else {
                var current = chars[pointer];
                if (!Character.isDigit(current)) {
                    endAndThen(PRECOMMENT);
                } else {
                    pointer++;
                }
            }
        }

        @Override
        void endAndThen(State next) {
            var cs = decodeChecksum(new String(chars, start, pointer - start));
            if ((checksum & 0xff) != cs) {
                throw new GCodeSyntaxException(line, "Checksum does not match");
            }
            super.endAndThen(PRECOMMENT);
        }
    };

    private final State PRECOMMENT = new State() {
        public void next() {
            var current = chars[pointer];
            if (current == ';') {
                pointer++;
            } else {
                start(COMMENT);
            }
        }
    };

    private final State COMMENT = new State() {
        @Override
        public void next() {
            pointer++;
        }

        @Override
        void endAndThen(State next) {
            pointer = chars.length;
            var element = new String(chars, start, pointer - start);
            var comm = decodeComment(element);
            elements.add(comm);
            super.endAndThen(next);
        }
    };

    private final State EOL = new State() {
        @Override
        public void next() {
            throw new IllegalStateException("Parser has already finished");
        }
    };

    private State state = DELIM;

}
