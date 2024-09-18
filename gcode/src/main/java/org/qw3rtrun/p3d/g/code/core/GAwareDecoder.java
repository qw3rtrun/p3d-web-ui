package org.qw3rtrun.p3d.g.code.core;

import java.util.List;
import java.util.Map;
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
}
