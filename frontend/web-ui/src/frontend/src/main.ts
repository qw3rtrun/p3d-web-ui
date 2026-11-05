// The theme's stylesheet, bundled rather than served from /static (Vite emits assets/p3d-ui.css),
// so that directory holds nothing but images. The themify icon font once imported beside it is
// gone: nothing ever used a `ti-` class, and three of its four font files were committed empty -
// the one with bytes was an SVG font browsers dropped support for years ago.
import './theme/style.css'

import * as bootstrap from 'bootstrap'
import {createApp, type Component} from 'vue'
import FarmDashboard from './components/FarmDashboard.vue'
import PrinterMonitor from './components/PrinterMonitor.vue'
import SettingsTab from './components/SettingsTab.vue'
import StorageTab from './components/StorageTab.vue'
import {startTheme} from './theme'

/**
 * Mounts the interactive parts of server-rendered pages.
 *
 * The UI is Thymeleaf pages with Vue islands in them, not a single-page application, so this entry
 * point owns no route table or root component - it finds the mount points a page declared and
 * mounts the component each one names:
 *
 * ```html
 * <div data-p3d="printer-monitor"
 *      data-uuid="..." data-name="alpha" data-role="OPERATOR"></div>
 * ```
 *
 * Props come from `data-` attributes rather than a fetch, since the server already knew them when
 * it rendered the page - the role in particular must come from there, as the browser holds no auth
 * state of its own. One bundle serves every page; a page with no mount point gets nothing.
 */

const ISLANDS: Record<string, Component> = {
    'farm-dashboard': FarmDashboard,
    'printer-monitor': PrinterMonitor,
    'settings-tab': SettingsTab,
    'storage-tab': StorageTab,
}

// `data-foo-bar` becomes the prop `fooBar`. `data-p3d` itself is the selector, not a prop, so it
// is dropped.
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
            // Loud on purpose: an empty region is otherwise indistinguishable from one with no data.
            console.error(`[p3d] no component registered for data-p3d="${name}"`)
            return
        }
        createApp(component, propsOf(element)).mount(element)
    })
}

// Bootstrap's own components are bundled, so every page has dropdowns, tabs and collapses - they
// used to come from a 1.6 MB vendor bundle plus a webjar ESM import on one page, so two copies of
// Bootstrap could be live at once on the monitor page.
function start() {
    startTheme(bootstrap)
    mountIslands()
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, {once: true})
} else {
    start()
}
