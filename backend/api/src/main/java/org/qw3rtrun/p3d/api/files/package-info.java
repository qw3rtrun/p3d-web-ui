/**
 * The G-code file vocabulary: what a stored file is, and how to put one in or get one out.
 *
 * <p>A fifth named interface rather than more records in {@code model} and another method on
 * {@code service}. The four that exist split the vocabulary by <em>who is allowed to use it</em> -
 * {@code management} is the roster's contract and {@code core} is denied it - and files are the
 * same kind of thing: a submodel that has no business storing G-code should not be able to name
 * {@code GcodeFileService}, and phases C and D add jobs and scheduling that will want the same
 * treatment.
 */
@org.springframework.modulith.NamedInterface("files")
package org.qw3rtrun.p3d.api.files;
