export default {
    data() {
        return {
            temp: 99,
            heating: false,
            target: -1,
            target_: ""
        }
    },
    created: function () {
        const events = new EventSource("api-printer/stream");
        events.onmessage = e => {
            console.log(e);
            var payload = JSON.parse(e.data);
            this.temp = payload.current;
            this.target = payload.target;
            this.heating = this.target > 0;
        }
    },
    methods: {
        update() {
            const requestOptions = {method: "POST"};
            fetch("api-printer/report-temp", requestOptions).then();
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
        <div class="card-header">Nozzle Temperature</div>
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
