package org.qw3rtrun.p3d.g.marlin.protocol

import org.qw3rtrun.p3d.g.code.dsl.GRs
import org.qw3rtrun.p3d.g.code.dsl.GRSDecoder

/**
 * `start` - sent once when the machine boots, before anything else.
 *
 * The spec is explicit that this line must not be augmented with version numbers and the like;
 * `M115` is what asks for those. So it carries nothing and, like [WaitRs], is its own decoder.
 *
 * Seeing it mid-session means the board reset, which is the one reason a host cares: the line
 * number sequence restarts and any send window is stale.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
object StartRs : GRs<StartRs>, GRSDecoder<StartRs> {

    override fun encode(): String = "start"

    override fun match(line: String): Boolean = line.trim().equals("start", ignoreCase = true)

    override fun decodeParams(line: String): StartRs = this
}
