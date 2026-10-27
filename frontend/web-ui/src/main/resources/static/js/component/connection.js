import client from 'client';

// One state, from one event.
//
// This component used to keep `connected` and `online` as separate booleans and set them from four
// different event names, with `online` turned on by *any* event arriving at all - so a printer that
// was connected but quiet read as offline, and nothing could have reconciled the two flags because
// the backend did not know which was true either. The server now owns a single connection state
// (OFFLINE / CONNECTING / ONLINE / IDLE / BUSY / ERROR) and announces every transition as one
// ConnectionStateChanged event, so this just mirrors it.
const LINKED = ["ONLINE", "IDLE", "BUSY", "ERROR"];

export default {
    props: {
        uuid: String
    },
    data() {
        return {
            state: "OFFLINE",
            attempt: 0,
            reason: null
        }
    },
    created() {
        this.api = client.api(this.uuid);
        this.stream = client.eventStream(this.uuid);
        this.stream.addEventListener("message", this.eventHandler);
        // The stream replays the current state to a new subscriber, so this is belt and braces for
        // the case where the page outlives a stream reconnect.
        this.api.connection().then(status => {
            if (status) {
                this.state = status.state;
                this.attempt = status.attempt;
                this.reason = status.reason;
            }
        });
    },
    beforeUnmount() {
        this.stream.removeEventListener("message", this.eventHandler);
    },
    computed: {
        connected() {
            return LINKED.includes(this.state);
        },
        label() {
            if (this.state === "CONNECTING" && this.attempt > 1) {
                return "CONNECTING (attempt " + this.attempt + ")";
            }
            return this.state;
        }
    },
    components: {},
    methods: {
        connect() {
            this.api.connect();
        },
        disconnect() {
            this.api.disconnect();
        },
        eventHandler(e) {
            const payload = JSON.parse(e.data);
            if (payload.type === "ConnectionStateChanged") {
                this.state = payload.event.to;
                this.attempt = payload.event.attempt;
                this.reason = payload.event.reason;
            }
        }
    },
    template: `
    <div>
        <div class="btn-wrapper">
            <a href="#" class="btn btn-otline-dark align-items-center"><i class="icon-share"></i> Share</a>
            <a href="#" class="btn btn-otline-dark"><i class="icon-printer"></i> Print</a>
            <a href="#" class="btn btn-primary text-white" @click="connect()"> Connect</a>
            <a href="#" class="btn btn-danger text-white" @click="disconnect()"> Disconnect</a>
            <span class="badge" :class="connected ? 'bg-success' : 'bg-secondary'" :title="reason">{{label}}</span>
        </div>
    </div>
`
}
