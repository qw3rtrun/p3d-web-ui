package org.qw3rtrun.p3d.g.code.core;

public class XorCheckSum implements CheckSum {
    private int sum = 0;

    @Override
    public void add(char ch) {
        sum ^= ch;
    }

    @Override
    public int get() {
        return sum & 0xff;
    }

    @Override
    public String getString() {
        return STR. "\{ get() }" ;
    }
}
