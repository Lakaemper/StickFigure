/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package utils;

import java.io.Serializable;

/**
 *
 * @author Rolf
 */
public class TupleD implements Serializable {

    public double first;
    public double second;

    public TupleD() {
    }

    public TupleD(double f, double s) {
        first = f;
        second = s;
    }

    public TupleD(double[] da) {
        first = da[0];
        second = da[1];
    }

    public TupleD(TupleD d) {
        first = d.first;
        second = d.second;
    }

    public void copy(TupleD t) {
        this.first = t.first;
        this.second = t.second;
    }

    public void set(double f, double s) {
        first = f;
        second = s;
    }

    @Override
    public String toString() {
        return ("(" + first + ", " + second + ")");
    }

    public double[] toDoubleArray() {
        double[] da = {first, second};
        return da;
    }

    public TupleD sub(TupleD t2) {
        TupleD diff = new TupleD();
        diff.first = this.first - t2.first;
        diff.second = this.second - t2.second;
        return (diff);
    }

    public void subLocal(TupleD t2) {
        this.first -= t2.first;
        this.second -= t2.second;
    }

    public TupleD add(TupleD t2) {
        TupleD diff = new TupleD();
        diff.first = this.first + t2.first;
        diff.second = this.second + t2.second;
        return (diff);
    }

    public void addLocal(TupleD t2) {
        this.first = this.first + t2.first;
        this.second = this.second + t2.second;
    }

    public TupleD times(double s) {
        TupleD scaled = new TupleD();
        scaled.first = this.first * s;
        scaled.second = this.second * s;
        return (scaled);
    }

    public void timesLocal(double s) {
        this.first = this.first * s;
        this.second = this.second * s;
    }

    public double dist(TupleD d2) {
        double dx = this.first - d2.first;
        double dy = this.second - d2.second;
        double distance = Math.sqrt(dx * dx + dy * dy);
        return (distance);
    }

    public double distSqr(TupleD d2) {
        double dx = this.first - d2.first;
        double dy = this.second - d2.second;
        double distance = dx * dx + dy * dy;
        return (distance);
    }

    public double length() {
        double dx = this.first;
        double dy = this.second;
        double l = Math.sqrt(dx * dx + dy * dy);
        return (l);
    }

    public double lengthSqr() {
        double dx = this.first;
        double dy = this.second;
        double l = dx * dx + dy * dy;
        return (l);
    }

    public double dot(TupleD t2) {
        return (first * t2.first + second * t2.second);
    }

    public double angleRad(TupleD d2) {
        double angle2 = Math.atan2(d2.second, d2.first);
        double angle1 = Math.atan2(second, first);
        double angle = angle2 - angle1;
        while (angle < -Math.PI) {
            angle += 2 * Math.PI;
        }
        while (angle > Math.PI) {
            angle -= 2 * Math.PI;
        }
        return (angle);
    }

    public TupleD rotate(double alphaRad, TupleD center) {
        TupleD d = this.sub(center);
        double c = Math.cos(alphaRad);
        double s = Math.sin(alphaRad);
        double x = d.first * c - d.second * s;
        double y = d.first * s + d.second * c;
        x += center.first;
        y += center.second;
        return (new TupleD(x, y));
    }

    public void rotateLocal(double alphaRad, TupleD center) {
        subLocal(center);
        double c = Math.cos(alphaRad);
        double s = Math.sin(alphaRad);
        double x = first * c - second * s;
        double y = first * s + second * c;
        x += center.first;
        y += center.second;
        first = x;
        second = y;
    }

    public boolean equals(TupleD t) {
        return (first == t.first && second == t.second);
    }

    public boolean normalizeLocal() {
        double len = length();
        if (len > 1e-10) {
            first /= len;
            second /= len;
            return (true);
        } else {
            return (false);
        }
    }

    public TupleD rot(double degree) {
        double c = Math.cos(degree / 180.0 * Math.PI);
        double s = Math.sin(-degree / 180.0 * Math.PI);
        double x = first * c - second * s;
        double y = first * s + second * c;
        return (new TupleD(x, y));
    }

    public void swapLocal() {
        double d = first;
        first = second;
        second = d;
    }

    public boolean isInsideRectangle(TupleD min, TupleD max) {
        boolean in = first >= min.first && first <= max.first;
        in = in && second >= min.second && second <= max.second;
        return in;
    }

    // M x t
    public TupleD matMultRight2x2(double[][] M) {
        TupleD r = new TupleD(0, 0);
        r.first = M[0][0] * this.first + M[0][1] * this.second;
        r.second = M[1][0] * this.first + M[1][1] * this.second;
        return r;
    }

    // M x t
    public void matMultRight2x2Local(double[][] M) {
        double x = M[0][0] * this.first + M[0][1] * this.second;
        double y = M[1][0] * this.first + M[1][1] * this.second;
        this.first = x;
        this.second = y;
    }

    // M x t
    // 3x3 homogeneous matrix
    public void matMultRight3x3Local(double[][] M) {
        double f = M[0][0] * this.first + M[0][1] * this.second + M[0][2];
        double s = M[1][0] * this.first + M[1][1] * this.second + M[1][2];
        this.first = f;
        this.second = s;
    }

    // M x t
    public TupleD matMultRight4x4xz(double[][] M) {
        TupleD r = new TupleD(0, 0);
        r.first = M[0][0] * this.first + M[0][2] * this.second + M[0][3];
        r.second = M[2][0] * this.first + M[2][2] * this.second + M[2][3];
        return r;
    }

    @Override
    public int hashCode() {
        double hc = (first % 100000.0) * 130000.0 + (second % 100000.0) * 170000.0;
        return ((int) Math.abs(hc));
    }

    // round
    public void roundLocal() {
        this.first = Math.round(first);
        this.second = Math.round(second);
    }

    // orthonormal local
    public void orthoNormalizeLocal() {
        double dummy = first;
        first = -second;
        second = dummy;
        normalizeLocal();
    }

    public void randomNormalLocal() {
        first = Math.random() - 0.5;
        second = Math.random() - 0.5;
        normalizeLocal();
    }

    public static TupleD random(TupleD range) {
        TupleD t = new TupleD();
        t.first = Math.random() * range.first;
        t.second = Math.random() * range.second;
        return t;
    }
}
