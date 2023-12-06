package org.qw3rtrun.p3d.g.code.core;

public class GCodeSyntaxException extends RuntimeException {
    public GCodeSyntaxException(String element, String message) {
        super(STR. "\{ element } \{message}" );
    }

    public GCodeSyntaxException(String element, String message, Throwable cause) {
        super(STR. "\{ element } \{message}" , cause);
    }
}
