package org.qw3rtrun.p3d.ui.view;

public interface Model {
    record Printer(int th1) {}

    record Heater(int th1, boolean heating, int target) {}
}
