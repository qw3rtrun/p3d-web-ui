/**
 * The handful of theme behaviours the pages actually use, without jQuery.
 *
 * Replaces three jQuery scripts (`off-canvas.js`, `hoverable-collapse.js`, `template.js`) and a
 * 1.6 MB vendor bundle; `template.js` did far more than this farm asked - a horizontal menu, a
 * PerfectScrollbar, a "buy the pro version" banner - and two of those jobs were already dead: active
 * sidebar highlighting was done server-side in `sidebar.html`, and the banner code queried a
 * `#proBanner` these templates do not contain, throwing a TypeError every load and silently skipping
 * everything after it, including the navbar's fixed-top handling.
 *
 * What survives is what the markup still hooks into: the two `data-bs-toggle` controls in `nav.html`
 * and the hover behaviour of a collapsed sidebar.
 */

// Collapses the sidebar to icons, or hides it outright on a layout that wants that.
function minimiseSidebar() {
    const body = document.body
    const hides = body.classList.contains('sidebar-toggle-display')
        || body.classList.contains('sidebar-absolute')
    body.classList.toggle(hides ? 'sidebar-hidden' : 'sidebar-icon-only')
}

// The small-screen sidebar, slid in and out over the content.
function toggleOffCanvas() {
    document.querySelectorAll('.sidebar-offcanvas')
        .forEach(sidebar => sidebar.classList.toggle('active'))
}

// Opens a submenu on hover while the sidebar is collapsed to icons - without it the icon-only
// sidebar is unusable, since the labels are what say where a link goes. Skipped on touch devices,
// where a tap should navigate rather than reveal.
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

// Only one submenu open at a time, what the theme's CSS assumes: Bootstrap fires show.bs.collapse
// on the element being opened, so everything else open gets hidden. getInstance rather than new -
// calling the constructor on an element Bootstrap already manages gives a second instance that
// fights the first.
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

// Bootstrap tooltips are opt-in; the theme ships only their CSS. data-bs-toggle="tooltip" is inert
// until something constructs a Tooltip - the monitor page needs one to explain a disabled tab, whose
// .disabled control fires no events of its own, so the tooltip hangs on a wrapper around it.
function enableTooltips(bootstrap: typeof import('bootstrap')) {
    document.querySelectorAll('[data-bs-toggle="tooltip"]')
        .forEach(element => new bootstrap.Tooltip(element))
}

export function startTheme(bootstrap: typeof import('bootstrap')) {
    document.querySelectorAll('[data-bs-toggle="minimize"]')
        .forEach(control => control.addEventListener('click', minimiseSidebar))

    document.querySelectorAll('[data-bs-toggle="offcanvas"]')
        .forEach(control => control.addEventListener('click', toggleOffCanvas))

    hoverToOpenWhenCollapsed()
    oneSubmenuAtATime(bootstrap)
    enableTooltips(bootstrap)
}
