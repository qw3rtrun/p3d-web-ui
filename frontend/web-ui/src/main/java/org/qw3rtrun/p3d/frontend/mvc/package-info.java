/**
 * The server-rendered pages: the dashboard, the add-printer form and the per-printer monitor.
 *
 * <p>A nested package, an internal of {@code frontend} and not a module of its own - it briefly
 * carried its own {@code @ApplicationModule}, which made Modulith report it as a nested module
 * sitting beside its container, since that annotation is what creates a module.
 */
package org.qw3rtrun.p3d.frontend.mvc;
