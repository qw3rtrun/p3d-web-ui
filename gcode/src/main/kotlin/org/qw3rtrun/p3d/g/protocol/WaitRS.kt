package org.qw3rtrun.p3d.g.protocol

import org.qw3rtrun.p3d.core.msg.WaitReceivedEvent

/**
 * `wait` - the machine's command buffers are empty and it is waiting for the next line.
 *
 * Carries nothing, so like [SimpleOkRs] it is a single object that is also its own decoder.
 * It answers to [WaitReceivedEvent] so it drops into the event pipeline that the older
 * `marlin/decoder` package already feeds.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
object WaitRs : GProtoRs<WaitRs>, GRsDecoder<WaitRs>, WaitReceivedEvent {

    override fun encode(): String = "wait"

    override fun match(line: String): Boolean = line.trim().equals("wait", ignoreCase = true)

    override fun decodeParams(line: String): WaitRs = this
}
