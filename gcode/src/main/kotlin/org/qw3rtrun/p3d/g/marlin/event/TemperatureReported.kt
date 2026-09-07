package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.core.msg.TemperatureReport
import org.qw3rtrun.p3d.core.msg.TemperatureReportedEvent

data class TemperatureReported(
    @get:JvmName("getHotend") val hotend: TemperatureReport?,
    @get:JvmName("getBed") val bed: TemperatureReport?
) : TemperatureReportedEvent {
    override fun hotend(): TemperatureReport? = hotend
    override fun bed(): TemperatureReport? = bed
}
