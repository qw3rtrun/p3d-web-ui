/**
 * The farm's own messages: the things that happen to a machine which no firmware can state.
 *
 * <p><strong>What is not here is the point.</strong> This package used to carry a record for every
 * reply a printer can send and every command one can be given - {@code TemperatureReported} beside
 * {@code TemperatureRs}, {@code SetFanSpeed} beside {@code marlin.command.SetFanSpeed} - a mirror
 * of {@code :gcode} maintained by hand, with a translation at each end. A Marlin farm is coupled to
 * G-code by construction, so the protocol's model is now the application's model: a reply travels
 * as the {@code GRs} the decoder produced, and a command is a {@code GRq}.
 *
 * <p>What remains is what {@code :gcode} has no word for, because it is about the host rather than
 * the machine: a connection moving, a block being acknowledged, the blocks a resynchronised session
 * abandoned, how far a transfer to a machine's card has got, a machine joining or leaving the farm,
 * and the roster news the {@code M}-prefixed events carry.
 */
@org.springframework.modulith.NamedInterface("msg")
package org.qw3rtrun.p3d.api.msg;
