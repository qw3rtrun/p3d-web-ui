export default {
    data() {
        return {
            connected: false,
            temp: "--",
            heating: false,
            tempReportPeriod: 1,
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
            fetch("api-printer/set-temp", requestOptions)
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
        autoReportTemp() {
            // Simple POST request with a JSON body using fetch
            const requestOptions = {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({period: this.tempReportPeriod})
            };
            let th1block = this;
            fetch("api-printer/auto-report-temp", requestOptions).then();
        },
    },
    template: `
<!--    <div class="card-header"></div>-->
    <div class="card-body">
        <div class="d-sm-flex justify-content-between align-items-start">
            <div>
                <h4 class="card-title card-title-dash">Nozzle Temperature</h4>
                <p class="card-subtitle card-subtitle-dash">Lorem ipsum dolor sit amet consectetur adipisicing elit</p>
            </div>
            <div>
                <button type="button" class="btn btn-primary text-white" @click="connect()">Connect</button>
                <button type="button" class="btn btn-primary text-white" @click="disconnect()">Disconnect</button>
                <input v-model="connected" class="form-check-input" type="checkbox" disabled>
            </div>           
            <div>
                <div class="dropdown">
                  <button class="btn btn-secondary dropdown-toggle toggle-dark mb-0 me-0" type="button" id="dropdownMenuButton2" data-bs-toggle="dropdown" aria-haspopup="true" aria-expanded="false"> Settings </button>
                  <div class="dropdown-menu" aria-labelledby="dropdownMenuButton2">
                    <h6 class="dropdown-header">Settings</h6>
                    <a class="dropdown-item" href="#">Action</a>
                    <a class="dropdown-item" href="#">Another action</a>
                    <a class="dropdown-item" href="#">Something else here</a>
                    <div class="dropdown-divider"></div>
                    <a class="dropdown-item" href="#">Separated link</a>
                  </div>
                </div>
            </div>
        </div>
        <div class="row pt-2">
            <div class="col">
                <h5 class="card-title">{{temp}} °C <span v-if="heating"> -> {{target}} °C</span></h5>
                <p class="card-text">Current Temperature.</p>
                <button type="button" class="btn btn-primary text-white" @click="update()">Update</button>
            </div>
            <div class="col">
                <input v-model="target_" placeholder="Temp">
                <p class="card-text">Set target Temp.</p>
                <button type="button" class="btn btn-primary text-white" @click="setHeatingTemp()">Set</button>                        
                <button type="button" class="btn btn-primary text-white" @click="stopHeating()">Cooldown</button>                        
            </div>
        </div>
        <div class="row">
            <div class="col">
                <input v-model="tempReportPeriod" placeholder="Seconds between reports" type="number" @change="autoReportTemp()">
                <p class="card-text">Temp Report Freq.</p>
                <button type="button" class="btn btn-primary text-white" @click="autoReportTemp()">Set</button>                                                
            </div>
        </div>
    </div>
`
}
