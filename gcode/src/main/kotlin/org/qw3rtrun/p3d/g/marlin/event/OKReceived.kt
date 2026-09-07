package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.core.msg.OKReceivedEvent

class OKReceived : OKReceivedEvent {
    override fun toString(): String = "ok"

    override fun equals(other: Any?): Boolean = other is OKReceived

    override fun hashCode(): Int = OKReceived::class.hashCode()

    companion object {
        @JvmField
        val INSTANCE: OKReceived = OKReceived()

        @JvmStatic
        fun getInstance(): OKReceived = INSTANCE
    }
}
