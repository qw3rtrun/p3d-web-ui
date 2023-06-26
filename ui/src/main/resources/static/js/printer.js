export default {
    data() {
        return {
            connected: false,
            temp: "--",
            heating: false,
            target: -1,
            target_: ""
        }
    },
    created: function () {
        const events = new EventSource("api-printer/stream");
        events.onmessage = e => {
            console.log(e);
            const payload = JSON.parse(e.data);
            if (payload.type === "OkTemperatureReported" || payload.type === "TemperatureReported") {
                this.connected = true;
                const report = payload.event.hotend;
                this.temp = report.current;
                this.target = report.target;
                this.heating = this.target > 0;
            } else if (payload.type === "DisconnectedEvent") {
                this.connected = false;
            } else if (payload.type === "ConnectedEvent") {
                this.connected = true;
            }
        }
    },
    methods: {
        update() {
            const requestOptions = {method: "POST"};
            fetch("api-printer/report-temp", requestOptions).then();
        },
        connect() {
            console.log("click Connect");
            // Simple POST request with a JSON body using fetch
            const requestOptions = {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({connect: true})
            };
            fetch("api-printer/connect", requestOptions).then();
        },
        disconnect() {
            console.log("click Disconnect");
            // Simple POST request with a JSON body using fetch
            const requestOptions = {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({connect: false})
            };
            fetch("api-printer/connect", requestOptions).then();
        },
        setHeatingTemp() {
            // Simple POST request with a JSON body using fetch
            const requestOptions = {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({index: 0, temp: this.target_})
            };
            fetch("api-printer/set-temp", requestOptions).then();
        },
        stopHeating() {
            // Simple POST request with a JSON body using fetch
            const requestOptions = {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({index: 0, temp: 0})
            };
            let th1block = this;
            fetch("printer/th1", requestOptions)
                .then(response => response.json())
                .then(data => {
                    th1block.temp = data.th1;
                    th1block.heating = data.heating;
                    th1block.target = data.target;
                });
            this.target = -1;
            this.heating = false;
            this.target_ = "";
        },
    },
    template: `
    <div class="card">
        <div class="card-header">
            <span>Nozzle Temperature</span>
            <a href="#" class="btn btn-primary" @click="connect()">Connect</a>
            <a href="#" class="btn btn-primary" @click="disconnect()">Disconnect</a>
            <input v-model="connected" class="form-check-input" type="checkbox" disabled>
        </div>
        <div class="card-body">
        <div class="row">
            <div class="col">
                <h5 class="card-title">{{temp}} °C <span v-if="heating"> -> {{target}} °C</span></h5>
                <p class="card-text">Current Temperature.</p>
                <a href="#" class="btn btn-primary" @click="update()">Update</a>
            </div>
            <div class="col">
                <input v-model="target_" placeholder="Temp">
                <p class="card-text">Set target Temp.</p>
                <a href="#" class="btn btn-primary" @click="setHeatingTemp()">Set</a>                        
                <a href="#" class="btn btn-primary" @click="stopHeating()">Cooldown</a>                        
            </div>
        </div>
        </div>
    </div>`
}
