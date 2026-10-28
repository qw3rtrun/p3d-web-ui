package org.qw3rtrun.p3d.api.msg;

/**
 * Something a user asked a machine to do, in the application's own words.
 *
 * <p><strong>Not a Marlin command.</strong> The REST surface used to bind `:gcode`'s generated
 * command classes directly, which made the HTTP contract follow the Marlin command set: a field
 * renamed by the generator was a breaking API change, and the shape of a request was whatever
 * Marlin's documentation happened to say that week. These records are the host's vocabulary, and
 * {@code core} translates them - the outbound half of the same split that put {@code ReplyEvents}
 * between the wire's reply model and this one.
 *
 * <p>It is also what lets {@code frontend} stop reaching into {@code core}: a controller builds one
 * of these and hands it to {@code PrinterControl}, an interface here, instead of calling the
 * runtime directly.
 *
 * <p><strong>Sealed, and the switch that reads it is exhaustive.</strong> That is possible here and
 * not for the two models either side of it: `:gcode`'s {@code GRq} has 295 implementations, and the
 * application's {@code GEvent} must stay open to the modules still to be written. This set is small,
 * closed, and owned entirely by {@code api} - so adding a command without teaching {@code core} to
 * send it fails the build, which is the property plan item A5 wanted and could not have.
 *
 * <p>The field names are the ones already on the wire ({@code index}, {@code temp},
 * {@code period}), so the browser's contract is unchanged by this moving.
 */
public sealed interface PrinterCommand
        permits SetHotendTarget, SetBedTarget, RequestTemperatureReport, SetTemperatureAutoReport,
                HomeAxes, MoveAxes, SetFanSpeed, StoreSettings, RequestPosition {
}
