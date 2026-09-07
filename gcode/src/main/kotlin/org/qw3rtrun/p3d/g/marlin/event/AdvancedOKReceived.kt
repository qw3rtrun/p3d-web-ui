package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.core.msg.AdvancedOkReceivedEvent

data class AdvancedOKReceived(
    @get:JvmName("getPlanner") val planner: Int,
    @get:JvmName("getBlockQueue") val blockQueue: Int,
    @get:JvmName("getLineNumber") val lineNumber: Int
) : AdvancedOkReceivedEvent {
    override fun planner(): Int = planner
    override fun blockQueue(): Int = blockQueue
    override fun lineNumber(): Int = lineNumber
}
