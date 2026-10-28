const streams = new Map();
const listeners = new Map();

// The CSRF token, read back out of the cookie the server set - plan item D3.
//
// The farm authenticates with a session cookie, because that is the only thing an EventSource can
// carry: it cannot set an Authorization header, so a bearer token would mean putting credentials
// in a query string. A cookie-authenticated API needs CSRF protection, and the server issues the
// token as a readable XSRF-TOKEN cookie precisely so this can echo it back in a header - a page on
// another origin can cause the browser to send the cookie, but cannot read it to build the header.
function csrfToken() {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
    return match ? decodeURIComponent(match[1]) : null;
}

// A 401 means the session has gone - a restart, or a timeout. Sending the user back to the login
// page is the only useful thing a page can do, and doing it here means no caller has to remember.
function signedOut(response) {
    if (response.status === 401) {
        window.location = "/login";
        return true;
    }
    return false;
}

function post(uuid, path, payload) {
    const jsonBody = JSON.stringify(payload);
    console.log(`{${uuid}${path}} <- ${jsonBody}`);
    const token = csrfToken();
    const headers = {"Content-Type": "application/json"};
    if (token) {
        headers["X-XSRF-TOKEN"] = token;
    }
    const requestOptions = {
        method: "POST",
        headers: headers,
        body: jsonBody
    };
    // Simple POST request with a JSON body using fetch
    return fetch(`/api-printer/${uuid}${path}`, requestOptions)
        .then(response => signedOut(response) ? {} : response.json())
        .then(json => {
            console.log(`{${uuid}${path}} -> ${json}`);
            return json;
        }, err => {
            console.log(`{${uuid}${path}} -> OK`)
            return {};
        });
}

// A GET returning JSON, for the handful of things the page asks rather than watches.
function get(uuid, path) {
    return fetch(`/api-printer/${uuid}${path}`)
        .then(response => {
            if (signedOut(response)) {
                return null;
            }
            return response.ok ? response.json() : null;
        }, () => null);
}

// One stream for the whole farm - plan item D2. The dashboard used to open one of these per
// printer, and each one instantiated a runtime on the server as a side effect: at forty machines
// that is forty sockets opened to render a page that is only looking.
//
// It opens with the current state of every machine, so a page can draw itself from the stream
// alone rather than fetching a list and then subscribing - which has a gap between the two where
// an event goes missing and a row stays stale until that printer next says something.
let farm = null;
const farmListeners = new Map();

function openFarmStream() {
    const stream = new EventSource("/api-farm/stream");
    stream.addEventListener("error", () => console.warn("{farm/stream} dropped"));
    return stream;
}

function openEventStream(uuid) {
    const stream = new EventSource(`/api-printer/${uuid}/stream`);
    stream.addEventListener("message", e => console.log(`{${uuid}/stream} -> ${e.data}`));
    return stream;
}

export default {
    eventStream(uuid) {
        const stream = streams.get(uuid) || openEventStream(uuid);
        streams.set(uuid, stream);
        return stream;
    },

    // Watch every machine at once. The callback gets the same EventMessage shape the per-printer
    // stream carries - {id, type, event} - so a dashboard routes on `id` and reads `event` exactly
    // as a monitor page already does.
    subscribeToFarm(cb) {
        if (farmListeners.has(cb)) {
            console.warn("Callback '" + cb + "' has already subscribed to the farm");
            return;
        }
        farm = farm || openFarmStream();
        const listener = e => cb(JSON.parse(e.data));
        farm.addEventListener("message", listener);
        farmListeners.set(cb, listener);
        console.log("Callback '" + cb + "' subscribed to the farm");
    },

    unsubscribeFromFarm(cb) {
        if (!farmListeners.has(cb)) {
            return;
        }
        farm.removeEventListener("message", farmListeners.get(cb));
        farmListeners.delete(cb);
    },

    subscribe(uuid, cb) {
        if (listeners.has(cb)) {
            console.warn("Callback '" + cb + "' (" + uuid + ") has already subscribed");
            return;
        }
        const stream = this.eventStream(uuid);
        const listener = e => {
            const payload = JSON.parse(e.data);
            cb(payload);
        }
        stream.addEventListener("message", listener);
        console.log("Callback '" + cb + "' (" + uuid + ") successfully subscribed");
        listeners.set(cb, listener);
    },

    unsubscribe(uuid, cb) {
        if (listeners.has(cb)) {
            const stream = streams.get(uuid);
            stream.removeEventListener(stream.get(cb));
            console.log("Callback '" + cb + "' (" + uuid + ") successfully unsubscribed");
        }
    },

    api(uuid) {
        return {
            connect() {
                post(uuid, "/connect", {connect: true}).then();
            },
            disconnect() {
                post(uuid, "/connect", {connect: false}).then();
            },
            // Where the machine's connection is, right now - the health signal. Before B3 the only
            // way to find out was to open the stream and wait for something to happen.
            connection() {
                return get(uuid, "/connection");
            },
            setHeatingTemp(index, temp) {
                post(uuid, "/set-temp", {index: index, temp: temp}).then();
            },
            setBedTemp(temp) {
                post(uuid, "/set-bed-temp", {temp: temp}).then();
            },
            stopHeating(index) {
                post(uuid, "/set-temp", {index: index, temp: 0}).then();
            },
            stopBedHeating() {
                post(uuid, "/set-bed-temp", {temp: 0}).then();

            },
            autoReportTemp(period) {
                post(uuid, "/auto-report-temp", {period: period}).then();
            }
        }
    }
}
