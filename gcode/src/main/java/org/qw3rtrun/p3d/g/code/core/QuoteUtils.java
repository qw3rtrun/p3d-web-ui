package org.qw3rtrun.p3d.g.code.core;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

@UtilityClass
public class QuoteUtils {

    public String quote(String str) {
        return "\"" + str.replace("\"", "\"\"") + "\"";
    }

    public String unquote(String str) {
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

    public GElement createQuote(String raw) {
        return raw.startsWith("\"")? GQuote.from(raw) : GQuoteField.from(raw);
    }
}
