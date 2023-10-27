package org.qw3rtrun.p3d.g.decoder;

import org.qw3rtrun.p3d.g.event.AdvancedOKReceived;
import org.qw3rtrun.p3d.g.event.OKReceived;
import org.qw3rtrun.p3d.core.msg.OKReceivedEvent;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OkDecoder implements GEventDecoder<OKReceivedEvent> {

    private static Pattern ADVANCED_OK_PATTERN = Pattern.compile(
            "^[oO][kK] +([PpBb])([0-9]+) +([PpBb])([0-9]+)(?> +([Nn])([0-9]+))?"
    );

    @Override
    public Optional<OKReceivedEvent> decode(String line) {
        var okLine = line.trim();
        if ("OK".equalsIgnoreCase(okLine))
            return Optional.of(new OKReceived());
        if ("OK".equalsIgnoreCase(okLine.substring(0, 2))) {
            return parseAdvanced(line);
        }
        return Optional.empty();
    }

    private Optional<OKReceivedEvent> parseAdvanced(String line) {
        Matcher matcher = ADVANCED_OK_PATTERN.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
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

        return Optional.of(new AdvancedOKReceived(planner, blockQueue, lineNumber));
    }
}
