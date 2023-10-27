import client from 'client';

export default {
    props: {
        uuid: String
    },
    data() {
        return {
            connected: false,
            online: false,
            counter: 0,
            text: "Sergei"
        }
    },
    created() {
        this.api = client.api(this.uuid);
        this.stream = client.eventStream(this.uuid);
        this.stream.addEventListener("message", this.eventHandler);
    },
    beforeUnmount() {
        this.stream.removeEventListener("message", this.eventHandler);
    },
    components : {
    },
    methods: {
        connect() {
            this.api.connect();
        },
        disconnect() {
            this.api.disconnect();
        },
        eventHandler(e) {
            this.online = true;
            const payload = JSON.parse(e.data);
            if (payload.type === "DisconnectedEvent") {
                this.connected = false;
            } else if (payload.type === "ConnectedEvent") {
                this.connected = true;
            } else if (payload.type === "MachineOfflineEvent") {
                this.online = false;
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
            <input v-model="online" class="form-check-input" type="checkbox" disabled>
        </div>
    </div>
`
}
