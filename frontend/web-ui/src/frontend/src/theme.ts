/**
 * The handful of theme behaviours the pages actually use, without jQuery.
 *
 * <strong>This replaces three scripts and a 1.6 MB vendor bundle.</strong> The theme shipped
 * `off-canvas.js`, `hoverable-collapse.js` and `template.js`, all jQuery, and `template.js` did
 * far more than this farm ever asked of it - a horizontal menu none of the pages have, a
 * PerfectScrollbar for panels none of them render, and a "buy the pro version" banner.
 *
 * <strong>Two of those jobs were already dead.</strong> `template.js` highlighted the active
 * sidebar link by matching `location.pathname`, which `sidebar.html` has done server-side for a
 * while - `th:classappend="${active == 'dashboard' ? 'active' : ''}"`. And its banner code ran
 * `document.querySelector('#proBanner').classList.add(...)` against a `#proBanner` these templates
 * do not contain, so it threw a TypeError every page load and everything after that line - the
 * navbar's fixed-top handling - had never run at all.
 *
 * So what is kept is what the markup still hooks into: the two `data-bs-toggle` controls in
 * `nav.html`, and the hover behaviour of a collapsed sidebar. Everything else is gone with the
 * library it needed.
 */

/** Collapses the sidebar to icons, or hides it outright on a layout that wants that. */
function minimiseSidebar() {
    const body = document.body
    const hides = body.classList.contains('sidebar-toggle-display')
        || body.classList.contains('sidebar-absolute')
    body.classList.toggle(hides ? 'sidebar-hidden' : 'sidebar-icon-only')
}

/** The small-screen sidebar, slid in and out over the content. */
function toggleOffCanvas() {
    document.querySelectorAll('.sidebar-offcanvas')
        .forEach(sidebar => sidebar.classList.toggle('active'))
}

/**
 * Opens a submenu on hover while the sidebar is collapsed to icons.
 *
 * Without it an icon-only sidebar is unusable: the labels are what say where a link goes, and
 * collapsing hides them. Skipped on touch devices, where there is no hover and a tap should
 * navigate rather than reveal.
 */
function hoverToOpenWhenCollapsed() {
    if ('ontouchstart' in document.documentElement) {
        return
    }
    document.querySelectorAll<HTMLElement>('.sidebar .nav-item').forEach((item) => {
        item.addEventListener('mouseenter', () => {
            if (document.body.classList.contains('sidebar-icon-only')) {
                item.classList.add('hover-open')
            }
        })
        item.addEventListener('mouseleave', () => item.classList.remove('hover-open'))
    })
}

/**
 * Only one submenu open at a time, which is what the theme's CSS assumes.
 *
 * Bootstrap fires `show.bs.collapse` on the element being opened; everything else in the sidebar
 * that is currently open gets hidden. `bootstrap.Collapse.getInstance` rather than `new` - calling
 * the constructor on an element Bootstrap already manages gives a second instance that fights the
 * first.
 */
function oneSubmenuAtATime(bootstrap: typeof import('bootstrap')) {
    const sidebar = document.querySelector('.sidebar')
    if (!sidebar) {
        return
    }
    sidebar.addEventListener('show.bs.collapse', (event) => {
        sidebar.querySelectorAll('.collapse.show').forEach((open) => {
            if (open !== event.target) {
                bootstrap.Collapse.getInstance(open)?.hide()
            }
        })
    })
}

export function startTheme(bootstrap: typeof import('bootstrap')) {
    document.querySelectorAll('[data-bs-toggle="minimize"]')
        .forEach(control => control.addEventListener('click', minimiseSidebar))

    document.querySelectorAll('[data-bs-toggle="offcanvas"]')
        .forEach(control => control.addEventListener('click', toggleOffCanvas))

    hoverToOpenWhenCollapsed()
    oneSubmenuAtATime(bootstrap)
}
