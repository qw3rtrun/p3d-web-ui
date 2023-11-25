const streams = new Map();
const listeners = new Map();

function post(uuid, path, payload) {
    const jsonBody = JSON.stringify(payload);
    console.log(`{${uuid}${path}} <- ${jsonBody}`);
    const requestOptions = {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: jsonBody
    };
    // Simple POST request with a JSON body using fetch
    return fetch(`/api-printer/${uuid}${path}`, requestOptions)
        .then(response => response.json())
        .then(json => {
            console.log(`{${uuid}${path}} -> ${json}`);
            return json;
        }, err => {
            console.log(`{${uuid}${path}} -> OK`)
            return {};
        });
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
