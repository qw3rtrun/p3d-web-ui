package org.qw3rtrun.p3d.terminal.msg;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">...</a>
 */
public sealed interface Replay {

    static Replay parse(String line) {
        return switch (line) {
            case String s when prefix(s, "wait") -> new Wait(s);
            case String s when prefix(s, "ok") -> Ok.parse(s);
            case String s when prefix(s, "Error:") -> new Error(s, s.substring(6).trim());
            case String s when prefix(s, "busy:") -> new Busy(s, s.substring(5).trim());
            case String s when prefix(s, "Resend:") -> new Resend(s, Replay.lineNumber(s.substring(7)));
            case String s when prefix(s, "rs:") -> new Resend(s, Replay.lineNumber(s.substring(3)));
            case String s when prefix(s, "!!") -> new Error(s, s.substring(2).trim());
            case String s when prefix(s, "fatal:") -> new Error(s, s.substring(6).trim());
            default -> new Message(line);
        };
    }

    private static boolean prefix(String line, String prefix) {
        return line.length() >= prefix.length() && prefix.equalsIgnoreCase(line.substring(0, prefix.length()));
    }

    private static int lineNumber(String raw) {
        return switch (raw.trim()) {
            case String s when prefix(s, "N:") -> Integer.parseInt(s.substring(2).trim());
            case String s when prefix(s, "N") -> Integer.parseInt(s.substring(1).trim());
            case String s -> Integer.parseInt(s.trim());
        };
    }

    String raw();

    record Resend(String raw, int number) implements Replay {
    }

    record Error(String raw, String message) implements Replay {
    }

    record Busy(String raw, String payload) implements Replay {
    }

    record Wait(String raw) implements Replay {
    }

    record Message(String raw) implements Replay {
    }

    record Ok(String raw, String payload, MetaData meta) implements Replay {

        private static Pattern ADVANCED_PATTERN = Pattern.compile(
                "([PpBb])([0-9]+) +([PpBb])([0-9]+)( +[Nn]([0-9]+))?"
        );

        public static Ok parse(String line) {
            var payload = line.substring(2).trim();
            var metaData = parseAdvanced(line);
            return new Ok(line, payload, metaData);
        }

        private static MetaData parseAdvanced(String payload) {
            Matcher matcher = ADVANCED_PATTERN.matcher(payload);
            if (!matcher.find()) {
                return null;
            }
            int planner = -1;
            int blockQueue = -1;
            int lineNumber = -1;
            switch (matcher.group(1)) {
                case "P":
                case "p":
                    planner = Integer.parseInt(matcher.group(2));
                    break;
                case "B":
                case "b":
                    blockQueue = Integer.parseInt(matcher.group(2));
                    break;
            }
            switch (matcher.group(3)) {
                case "B":
                case "b":
                    blockQueue = Integer.parseInt(matcher.group(4));
                    break;
                case "P":
                case "p":
                    planner = Integer.parseInt(matcher.group(4));
                    break;
            }
            if ("N".equalsIgnoreCase(matcher.group(5))) {
                lineNumber = Integer.parseInt(matcher.group(6));
            }

            return new MetaData(payload, planner, blockQueue, lineNumber);
        }

    }

}



