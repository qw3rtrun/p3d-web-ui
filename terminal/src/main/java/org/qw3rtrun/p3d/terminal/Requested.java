package org.qw3rtrun.p3d.terminal;

import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public class Requested {

    private long n = 0;

    public void inc(long i) {
        if (n != Long.MAX_VALUE) {
            n += i;
        }
    }

    public void inc() {
        inc(1);
    }

    public void dec(long i) {
        if (n != Long.MAX_VALUE) {
            n = Math.max(0, n - i);
        }
    }

    public void dec() {
        dec(1);
    }

    public long get() {
        return n;
    }

    public long reset() {
        var r = n;
        n = 0;
        return r;
    }

    @Override
    public String toString() {
        return n == Long.MAX_VALUE ? "unbounded" : String.valueOf(n);
    }
}
