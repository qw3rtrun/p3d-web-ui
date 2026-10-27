/**
 * The {@code files} submodel: stored G-code, addressed by the hash of its content.
 *
 * <p><strong>It depends on no other application module.</strong> {@code api::files} is its whole
 * contract - {@code GcodeFileService} going in, {@code GcodeFile} coming out - and the empty
 * remainder of {@code allowedDependencies} enforces it. The REST surface that uploads and downloads
 * belongs to {@code frontend}, which is where {@code doc/sub_boundaries.txt} puts every server API,
 * so there is no WebFlux here and the bytes cross as {@code Publisher<ByteBuffer>}.
 *
 * <p>{@code model} and {@code service} are nested packages and so internal by Modulith's default,
 * which is right for both: {@code GcodeFileEntity} is a row and {@code GcodeBlobStore} knows where
 * the bytes are kept, and neither is anybody else's business.
 *
 * <p>It is the second persistence module, and the first one to make the single application-wide
 * changelog untenable - its tables are declared in {@code db/changelog/gcode-files.xml} here, and
 * {@code :app} is what assembles that with the roster's into one schema.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "G-code files",
        allowedDependencies = {"api::files"})
package org.qw3rtrun.p3d.files;
