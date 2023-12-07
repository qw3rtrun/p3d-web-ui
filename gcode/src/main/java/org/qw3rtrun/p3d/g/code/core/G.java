package org.qw3rtrun.p3d.g.code.core;

import java.math.BigDecimal;

public class G {

    public static GCommand M(int n, GField... fields) {
        return new GCommand(new GIntField('M', n), fields);
    }

    public static GCommand G(int n, GField... fields) {
        return new GCommand(new GIntField('G', n), fields);
    }

    public static GIntField T(int n) {
        return new GIntField('T', n);
    }

    public static GIntField S(int n) {
        return new GIntField('S', n);
    }

    public static GIntField P(int n) {
        return new GIntField('P', n);
    }

    public static GIntField F(int n) {
        return new GIntField('F', n);
    }

    public static GIntField N(int n) {
        return new GIntField('N', n);
    }

    public static GDoubleField X(double n) {
        return new GDoubleField('X', new BigDecimal(n));
    }

    public static GDoubleField Y(double n) {
        return new GDoubleField('Y', new BigDecimal(n));
    }

    public static GDoubleField Z(double n) {
        return new GDoubleField('Z', new BigDecimal(n));
    }

    public static GDoubleField I(double n) {
        return new GDoubleField('I', new BigDecimal(n));
    }

    public static GDoubleField J(double n) {
        return new GDoubleField('J', new BigDecimal(n));
    }

    public static GDoubleField D(double n) {
        return new GDoubleField('D', new BigDecimal(n));
    }

    public static GDoubleField H(double n) {
        return new GDoubleField('H', new BigDecimal(n));
    }

    public static GDoubleField R(double n) {
        return new GDoubleField('I', new BigDecimal(n));
    }

    public static GDoubleField E(double n) {
        return new GDoubleField('I', new BigDecimal(n));
    }

//    U,V,W	Additional axis coordinates (RepRapFirmware)
//    Innn	Parameter - X-offset in arc move; integral (Ki) in PID Tuning
//    Jnnn	Parameter - Y-offset in arc move
//    Dnnn	Parameter - used for diameter; derivative (Kd) in PID Tuning
//    Hnnn	Parameter - used for heater number in PID Tuning
//    Fnnn	Feedrate in mm per minute. (Speed of print head movement)
//    Rnnn	Parameter - used for temperatures
//    Qnnn	Parameter - not currently used
//    Ennn	Length of extrudate. This is exactly like X, Y and Z, but for the length of filament to consume.
//    Nnnn	Line number. Used to request repeat transmission in the case of communications errors.


}
