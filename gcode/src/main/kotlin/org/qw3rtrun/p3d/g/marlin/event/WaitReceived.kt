package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.core.msg.WaitReceivedEvent

class WaitReceived : WaitReceivedEvent {
    override fun toString(): String = "WaitReceived()"

    override fun equals(other: Any?): Boolean = other is WaitReceived

    override fun hashCode(): Int = WaitReceived::class.hashCode()

    companion object {
        @JvmField
        val INSTANCE: WaitReceived = WaitReceived()

        @JvmStatic
        fun getInstance(): WaitReceived = INSTANCE
    }
}
