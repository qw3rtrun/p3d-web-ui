package org.qw3rtrun.p3d.g.code.cmd;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.g.code.core.GCodeSyntaxException;
import org.qw3rtrun.p3d.g.code.core.GCoreCodec;

@RequiredArgsConstructor
public class GLineCodec {

    private final GCoreCodec codec;

    public String encode(GLine line) {
        return codec.encode(line.asElements());
    }

    public String encode(GCommand cmd) {
        return encode(cmd.asLine());
    }

    public GLine decode(String line) throws GCodeSyntaxException {
        var elements = codec.decode(line);
        return GLine.from(elements);
    }
}
