package org.qw3rtrun.p3d.core;


import lombok.Getter;
import org.qw3rtrun.p3d.core.msg.TemperatureReport;
import org.qw3rtrun.p3d.core.msg.TemperatureReportedEvent;

@Getter
public class TemperatureControl {

    private TemperatureReport hotend = new TemperatureReport(-1, -1, -1);
    private TemperatureReport bed = new TemperatureReport(-1, -1, -1);

    public boolean on(TemperatureReportedEvent event) {
        var isChanged = !hotend.equals(event.hotend()) || !bed.equals(event.bed());
        this.hotend = event.hotend();
        this.bed = event.bed();
        return isChanged;
    }

}
