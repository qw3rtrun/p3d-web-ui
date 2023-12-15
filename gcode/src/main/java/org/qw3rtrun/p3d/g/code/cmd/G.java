package org.qw3rtrun.p3d.g.code.cmd;

import org.qw3rtrun.p3d.g.code.core.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class G {

    public static GCommand M(int n, GField... fields) {
        return new GCommand(-1, new GIntField('M', n), Arrays.asList(fields), Collections.emptyList(), null);
    }

    public static GCommand M(int n, GComment com, GField... fields) {
        return new GCommand(new GIntField('M', n), Arrays.asList(fields), Collections.emptyList(), com);
    }

    public static GCommand M(int n, GLiteral lit, GField... fields) {
        return new GCommand(new GIntField('M', n), Arrays.asList(fields), List.of(lit), null);
    }

    public static GCommand M(int n, GLiteral lit, GComment com, GField... fields) {
        return new GCommand(new GIntField('M', n), Arrays.asList(fields), List.of(lit), com);
    }

    public static GCommand M(int n, GElement... elements) {
        var c = new GIntField('M', n);
        var list = new ArrayList<GElement>();
        list.add(c);
        list.addAll(Arrays.asList(elements));
        return GCommand.from(list);
    }

    public static GCommand G(int n, GField... fields) {
        return new GCommand(new GIntField('G', n), Arrays.asList(fields));
    }

    public static GCommand G(int n, GElement... elements) {
        var c = new GIntField('G', n);
        var list = new ArrayList<GElement>();
        list.add(c);
        list.addAll(Arrays.asList(elements));
        return GCommand.from(list);
    }

    public static GIntField T(int n) {
        return new GIntField('T', n);
    }

    public static GIntField S(int n) {
        return new GIntField('S', n);
    }

    public static GFlagField B() {
        return new GFlagField('B');
    }

    public static GIntField B(int n) {
        return new GIntField('B', n);
    }

    public static GIntField P(int n) {
        return new GIntField('P', n);
    }

    public static GIntField F(int n) {
        return new GIntField('F', n);
    }

    public static GStrField F(String val) {
        return new GStrField('F', val);
    }

    public static GLiteral LIT(String val) {
        return QuoteUtils.createLiteral(val);
    }

    public static GIntField N(int n) {
        return new GIntField('N', n);
    }

    public static GIntField Q(int n) {
        return new GIntField('Q', n);
    }

    public static GFlagField O() {
        return new GFlagField('O');
    }

    public static GDoubleField X(double n) {
        return new GDoubleField('X', new BigDecimal(n));
    }

    public static GFlagField X() {
        return new GFlagField('X');
    }

    public static GDoubleField Y(double n) {
        return new GDoubleField('Y', new BigDecimal(n));
    }

    public static GFlagField Y() {
        return new GFlagField('Y');
    }

    public static GStrField Y(String val) {
        return new GStrField('Y', val);
    }

    public static GDoubleField Z(double n) {
        return new GDoubleField('Z', new BigDecimal(n));
    }

    public static GFlagField Z() {
        return new GFlagField('Z');
    }

    public static GDoubleField I(double n) {
        return new GDoubleField('I', new BigDecimal(n));
    }

    public static GDoubleField U(double n) {
        return new GDoubleField('U', new BigDecimal(n));
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

    public static GComment COM(String text) {
        return new GComment(text);
    }

    public static GFlagField A() {
        return new GFlagField('A');
    }

    public static GFlagField C() {
        return new GFlagField('C');
    }

    public static GFlagField T() {
        return new GFlagField('T');
    }

    public static GDoubleField T(double n) {
        return new GDoubleField('T', new BigDecimal(n));
    }

    public static GFlagField I() {
        return new GFlagField('I');
    }

    public static GFlagField J() {
        return new GFlagField('J');
    }

    public static GFlagField E() {
        return new GFlagField('E');
    }

    public static GFlagField P() {
        return new GFlagField('P');
    }

    public static GFlagField R() {
        return new GFlagField('R');
    }

    public static GDoubleField P(double n) {
        return new GDoubleField('P', new BigDecimal(n));
    }

    public static GDoubleField A(double n) {
        return new GDoubleField('A', new BigDecimal(n));
    }

    public static GDoubleField B(double n) {
        return new GDoubleField('B', new BigDecimal(n));
    }

    public static GDoubleField C(double n) {
        return new GDoubleField('C', new BigDecimal(n));
    }

    public static GDoubleField S(double n) {
        return new GDoubleField('S', new BigDecimal(n));
    }

    public static GFlagField S() {
        return new GFlagField('S');
    }

    public static GFlagField W() {
        return new GFlagField('W');
    }

    public static GIntField W(int n) {
        return new GIntField('W', n);
    }

    public static GDoubleField W(double n) {
        return new GDoubleField('W', new BigDecimal(n));
    }

    public static GDoubleField Q(double n) {
        return new GDoubleField('Q', new BigDecimal(n));
    }

    public static GIntField A(int n) {
        return new GIntField('A', n);
    }

    public static GStrField W(String text) {
        return new GStrField('W', text);
    }

    public static GStrField H(String text) {
        return new GStrField('H', text);
    }

    public static GIntField L(int n) {
        return new GIntField('L', n);
    }

    public static GDoubleField L(double n) {
        return new GDoubleField('L', new BigDecimal(n));
    }

    public static GIntField V(int n) {
        return new GIntField('V', n);
    }

    public static GIntField G(int n) {
        return new GIntField('G', n);
    }

    public static GIntField O(int n) {
        return new GIntField('O', n);
    }

    public static GDoubleField K(double n) {
        return new GDoubleField('K', new BigDecimal(n));
    }

    public static GFlagField K() {
        return new GFlagField('K');
    }

    public static GFlagField F() {
        return new GFlagField('F');
    }

    public static GDoubleField F(double n) {
        return new GDoubleField('F', new BigDecimal(n));
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
