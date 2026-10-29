/**
 * The {@code identity} submodel: who may use this farm - plan item D3.
 *
 * <p><strong>It owns the people, not the sessions.</strong> One table, one question - is this
 * person who they say they are, and what are they allowed to do. Authenticating a <em>request</em>
 * is the frontend's business, because that is the only place a request exists; this module never
 * sees one, and carries no filter chain, no login page and no web dependency at all.
 *
 * <p>That split is what lets the password hashing live here rather than next to the security
 * configuration. {@code FarmUserDirectory.add} takes a raw password and returns a
 * {@code FarmUser} carrying only a hash, so cleartext exists for the length of one call and no
 * other module can store, log or forward one.
 *
 * <p>It is the roster of <em>people</em> and deliberately not part of the roster of
 * <em>machines</em>. Putting users in {@code printer-management} would mean a module that answers
 * "what printers are there" also answers "who may log in", and the two have different audiences,
 * different lifetimes and - once this farm leaves the bench - different people administering them.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {"api::identity"})
package org.qw3rtrun.p3d.identity;
