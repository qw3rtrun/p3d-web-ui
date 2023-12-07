package org.qw3rtrun.p3d.g.code.core;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.qw3rtrun.p3d.g.code.core.G.N;

@RequiredArgsConstructor
public class GCheckSumCodec {

    private final GCoreCodec codec;

    public String encode(int n, GCommand cmd) {
        var elements = cmd.asElements();
        elements.add(0, N(n));
        var str = codec.encode(elements);
        return str+"*"+checksum(str);
    }

    public int checksum(String str) {
        var cmd = str.toCharArray();
        int cs = 0;
        for(int i = 0; i< cmd.length && cmd[i] != '*'; i++)
            cs = cs ^ cmd[i];
        cs &= 0xff;
        return cs;
    }

    public GCommand decode(String line) throws GCodeSyntaxException {
        if (line.length() < 7 || line.charAt(line.length()-3) != '*') {
            throw new GCodeSyntaxException(line, "Should contain linenumber (Nnnn) amd checksum (*71)");
        }
        var checksum = line.substring(line.length() - 2);
        var elems = codec.decode(line.substring(0, line.length() - 3));
        if (elems.size() < 2 || !(elems.get(0) instanceof GIntField)) {
            throw new GCodeSyntaxException(line, "Should have format Nnnn [...GCode...]*71");
        }
        var ln = (GIntField)elems.get(0);
        return null; //TODO

    }
}
