package org.qw3rtrun.p3d.g.event

import org.qw3rtrun.p3d.core.msg.OKReceivedEvent
import org.qw3rtrun.p3d.core.msg.TemperatureReport
import org.qw3rtrun.p3d.core.msg.TemperatureReportedEvent

data class OkTemperatureReported(
    @get:JvmName("getHotend") val hotend: TemperatureReport?,
    @get:JvmName("getBed") val bed: TemperatureReport?
) : OKReceivedEvent, TemperatureReportedEvent {
    override fun hotend(): TemperatureReport? = hotend
    override fun bed(): TemperatureReport? = bed
}
