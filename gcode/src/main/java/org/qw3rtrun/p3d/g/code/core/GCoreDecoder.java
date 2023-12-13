package org.qw3rtrun.p3d.g.code.core;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;

public class GCoreDecoder {
    private final String line;
    private final char[] chars;
    private final CheckSum checksum;

    int pointer = 0;

    int start = 0;

    List<GElement> elements = new ArrayList<>();

    public GCoreDecoder(@NonNull String line, @NonNull CheckSum checkSum) {
        this.line = line;
        this.chars = line.toCharArray();
        this.checksum = checkSum;
    }

    public List<GElement> parse() throws GCodeSyntaxException {

        while (state != EOL) {
            state.process();
        }
        return elements;
    }

    private int decodeChecksum(String str) {
        try {
            return Integer.parseInt(str.substring(1));
        } catch (NumberFormatException e) {
            throw new GCodeSyntaxException(str, "Checksum should be a number");
        }
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
                checksum.add(current);
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
                    case '"' -> start(QUOTE);
                    case ';' -> start(COMMENT);
                    case '*' -> start(CHECKSUM);
                    default -> {
                        checksum.add(current);
                        pointer++;
                    }
                }
            } else {
                switch (current) {
                    case '"' -> {
                        checksum.add(current);
                        pointer++;
                        state = QUOTE;
                    }
                    case '*' -> endAndThen(CHECKSUM);
                    case ' ' -> endAndThen(DELIM);
                    case ';' -> endAndThen(COMMENT);
                    default -> {
                        checksum.add(current);
                        pointer++;
                    }
                }
            }
        }

        void endAndThen(State next) {
            elements.add(GField.from(new String(chars, start, pointer - start)));
            super.endAndThen(next);
        }
    };

    private final State QUOTE = new State() {
        @Override
        void next() throws GCodeSyntaxException {
            var current = chars[pointer];
            checksum.add(current);
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
            var raw = new String(chars, start, pointer - start);
            var s = QuoteUtils.createQuote(raw);
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
            if (checksum.get() != cs) {
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
            var raw = new String(chars, start, pointer - start);
            var comm = GComment.from(raw);
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
