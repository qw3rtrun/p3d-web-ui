/**
 * The server-rendered pages: the dashboard, the add-printer form and the per-printer monitor.
 *
 * <p>A nested package and therefore an <strong>internal of {@code frontend}</strong>, not a module
 * of its own. It carried an {@code @ApplicationModule} of its own briefly, which made Modulith
 * report it as a nested module sitting beside the one that contains it - the annotation is what
 * creates a module, so a package that should be an internal must not have one.
 *
 * <p>Thymeleaf and REST are one submodel because they are one surface: the same application serves
 * the pages and answers the fetches those pages make, and a boundary between them would have no
 * rule to enforce.
 */
package org.qw3rtrun.p3d.frontend.mvc;
