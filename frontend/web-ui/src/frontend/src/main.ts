// The theme's stylesheet, bundled rather than served from /static. Vite emits it as
// assets/p3d-ui.css, which is what lets that directory hold nothing but images.
//
// The themify icon font that used to be imported beside it is gone: no template or component ever
// used a `ti-` class, and three of its four font files were committed EMPTY - so the only format
// with any bytes in it was the SVG font, which browsers dropped support for years ago. It could
// never have rendered an icon.
import './theme/style.css'

import * as bootstrap from 'bootstrap'
import {createApp, type Component} from 'vue'
import FarmDashboard from './components/FarmDashboard.vue'
import PrinterMonitor from './components/PrinterMonitor.vue'
import {startTheme} from './theme'

/**
 * Mounts the interactive parts of server-rendered pages.
 *
 * The UI is Thymeleaf pages with Vue **islands** in them, not a single-page application, so this
 * entry point does not own a route table or a root component. It finds the mount points a page
 * declared and mounts the component each one names:
 *
 * ```html
 * <div data-p3d="printer-monitor"
 *      data-uuid="..." data-name="alpha" data-role="OPERATOR"></div>
 * ```
 *
 * **Props come from `data-` attributes rather than from a fetch**, because the server already knew
 * them when it rendered the page. That is the point of server-side rendering: no island should
 * have to ask the backend for something the document it is mounted in could have carried - and
 * the role in particular must come from the server, since the browser holds no auth state of its
 * own.
 *
 * One bundle serves every page. A page that declares no mount point simply gets nothing.
 */

const ISLANDS: Record<string, Component> = {
    'farm-dashboard': FarmDashboard,
    'printer-monitor': PrinterMonitor,
}

/**
 * `data-foo-bar` becomes the prop `fooBar`, which is how Vue would have spelled it.
 *
 * `data-p3d` itself is the selector, not a prop, so it is dropped.
 */
function propsOf(element: HTMLElement): Record<string, string> {
    const props: Record<string, string> = {}
    for (const [key, value] of Object.entries(element.dataset)) {
        if (key !== 'p3d' && value !== undefined) {
            props[key] = value
        }
    }
    return props
}

function mountIslands() {
    document.querySelectorAll<HTMLElement>('[data-p3d]').forEach((element) => {
        const name = element.dataset.p3d
        const component = name ? ISLANDS[name] : undefined

        if (!component) {
            // Loud on purpose: a mount point whose component was renamed renders nothing at all,
            // and an empty region on a page is otherwise indistinguishable from one with no data.
            console.error(`[p3d] no component registered for data-p3d="${name}"`)
            return
        }
        createApp(component, propsOf(element)).mount(element)
    })
}

/**
 * Bootstrap's own components are bundled, so every page has dropdowns, tabs and collapses.
 *
 * They used to come from a 1.6 MB vendor bundle on every page plus an ESM import from a webjar on
 * one, which meant two copies of Bootstrap could be live at once on the monitor page.
 */
function start() {
    startTheme(bootstrap)
    mountIslands()
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, {once: true})
} else {
    start()
}
