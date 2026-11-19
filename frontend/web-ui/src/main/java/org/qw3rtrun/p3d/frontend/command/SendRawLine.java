package org.qw3rtrun.p3d.frontend.command;

/**
 * One line of G-code an operator typed at the Terminal tab - issue #135.
 *
 * <p><strong>Not a {@link PrinterCommand}</strong>, and that is the feature rather than an omission:
 * there is no {@code toMarlin()} to write, because the line already <em>is</em> Marlin and
 * interpreting it is the one thing this route must not do. {@link UploadToCard} in this package is
 * the existing precedent for a request record that is not a command.
 *
 * <p><strong>And no validation here</strong>, unlike {@link SdFileName}'s check in
 * {@code UploadToCard}'s compact constructor. The authoritative gate is in {@code core}, where no
 * caller can bypass it - {@code PrinterControl.sendRaw} is a public method of the {@code api}
 * module and the browser is not its only possible caller - and a second half-gate in the web layer
 * would be two mechanisms for one rule, drifting apart the first time either is edited. A refused
 * line comes back as {@code MALFORMED}, which {@code Outcomes} maps to 400 with the reason in the
 * body.
 *
 * @param line the line as typed, with no {@code N} field and no checksum. Both are added by the send
 *             window, and what actually went on the wire comes back on
 *             {@code GET /api-printer/&#123;id&#125;/terminal/stream}
 */
public record SendRawLine(String line) {
}
