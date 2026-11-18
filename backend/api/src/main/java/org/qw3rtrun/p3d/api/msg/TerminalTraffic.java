package org.qw3rtrun.p3d.api.msg;

/**
 * One line as it crossed the wire, in whichever direction - what a per-machine console shows.
 *
 * <p>A host-side fact like {@link BlockAcknowledged} and {@link BlocksAbandoned} rather than
 * something a machine said: a console shows the <em>bytes</em>, and most of them no decoded reply
 * can restate. Censused against MarlinSim (Marlin bugfix-2.1.x) on {@code localhost:8099} on
 * 2026-11-18, 106 received lines, each classified by whether the farm's existing decoded-reply
 * stream can reproduce it:
 * <pre>
 *   UNCLAIMED - nothing reaches a subscriber    10   the whole boot banner; ReplyFlux drops these
 *   RENDERABLE from the decoded event           81
 *   LOSSY - encode() is not the line             3   `echo: Last Updated:` -&gt; `echo:Last Updated:`
 *   CONSUMED by the transport as flow control   12   every `ok P15 B3`
 * </pre>
 * 25 of 106 cannot be rendered from a decode at all, which is why the terminal carries raw lines
 * with the decode as an <em>annotation</em>, and is not a filtered view of
 * {@code PrinterControl.updates(UUID)}.
 *
 * @param direction which way the line crossed the wire
 * @param raw       the line exactly as it crossed, never a reconstruction. For {@code SENT} that is
 *                  the <strong>framed</strong> block ({@code N12 M105*31}) and not what the operator
 *                  typed - the {@code N} field and the checksum are added by the send window, and a
 *                  console showing the typed form would show what was asked for rather than what
 *                  happened, which is precisely where a line-numbering fault lives. For
 *                  {@code RECEIVED} it is the line trimmed, as {@code HostTerminal.ReplyFlux}
 *                  produces it
 * @param decodedAs the decoded reply's <strong>simple class name</strong>, or null when no decoder
 *                  claimed the line. A name and not the {@code GRs<?>} itself because
 *                  {@link EventMessage} carries no {@code @JsonTypeInfo} and declares its payload as
 *                  {@code Object}, so a nested reply would reach the browser as anonymous fields
 *                  with nothing saying what they are - useless to a console that wants a type badge;
 *                  and because a reply model restated on this boundary is the second copy of
 *                  {@code :gcode}'s model that {@code reactive-transport-dev} rules out. The full
 *                  decode is already on the monitor stream for every reply published there
 * @param at        epoch milliseconds. Deliberately a {@code long} rather than an {@code Instant} or
 *                  a {@code LocalDateTime}: a number needs no Jackson date module, and it is what
 *                  makes a <em>replayed</em> console honest rather than history presented as news -
 *                  the terminal sink replays its last 256 lines, where the monitor sink replays
 *                  nothing
 */
public record TerminalTraffic(Direction direction, String raw, String decodedAs, long at) {

    public enum Direction { SENT, RECEIVED }
}
