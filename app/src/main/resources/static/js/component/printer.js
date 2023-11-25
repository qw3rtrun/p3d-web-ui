import client from 'client';
import TemperatureControl from "./temperature-control.js";
import {PrinterState} from "../lib/printer-state.js"

export default {
    props: {
        uuid: String,
        name: String,
        connection: {
            host: String,
            port: Number
        }
    },
    data() {
        return {
            state: new PrinterState(this.uuid, this.name),
            connected: false,
            online: false,
            tempReportPeriod: 1,
            bed: {
                temp: 0,
                heating: false,
                target: 0,
                target_: 0,
            }
        }
    },
    created() {
        this.api = client.api(this.uuid);
        this.stream = client.eventStream(this.uuid);
        this.stream.addEventListener('message', this.eventHandler);
    },
    beforeUnmount() {
        this.stream.removeEventListener("message", this.eventHandler);
    },
    components: {
        TemperatureControl
    },
    methods: {
        eventHandler(e) {
            this.online = true;
            const payload = JSON.parse(e.data);
            if (payload.type === "OkTemperatureReported" || payload.type === "TemperatureReported") {
                this.connected = true;
                const report = payload.event.hotend;
                if (this.target !== report.target) {
                    this.target = report.target;
                    this.target_ = report.target;
                }
                this.heating = this.target > 0;

                const bedReport = payload.event.bed;
                this.bed.temp = bedReport.current;
                if (this.bed.target !== bedReport.target) {
                    this.bed.target = bedReport.target;
                    this.bed.target_ = bedReport.target;
                }
                this.bed.heating = this.bed.target > 0;
            } else if (payload.type === "DisconnectedEvent") {
                this.connected = false;
            } else if (payload.type === "ConnectedEvent") {
                this.connected = true;
            } else if (payload.type === "MachineOfflineEvent") {
                this.online = false;
            }
        },
        setBedTemp() {
            this.api.setBedTemp(this.bed.target_);
        },
        stopBedHeating(){
            this.api.setBedTemp(0);
        },
        autoReportTemp() {
            this.api.autoReportTemp(this.tempReportPeriod);
        }
    },
    template: `
<!--    <div class="card-header"></div>-->
    <div class="card-body">
        <div class="d-sm-flex justify-content-between align-items-start">
            <div>
                <h4 class="card-title card-title-dash">{{name}}</h4>
                <p class="card-subtitle card-subtitle-dash">{{uuid}} {{connection}}</p>
            </div>         
        </div>
        <TemperatureControl name="Nozzle 1" 
            :current="this.state.temperature.hotend.current"
            :value="this.state.temperature.hotend.target"
            :power="this.state.temperature.hotend.power" 
            :presets="[
                {label: 'ABS', value: 250},
                {label: 'PETG', value: 230},
                {label: 'PLA', value: 210},
                {label: 'TPU', value: 190},
            ]"
            @change="(t) => api.setHeatingTemp(0, t)" 
        />

        <div class="row pt-2">
            <div class="col-3 align-self-center">
                <h4 class="card-title">{{bed.temp}} °C <span v-if="bed.heating"> -> {{bed.target}} °C</span></h4>
            </div>
            <div class="col-auto align-self-end">
                <button type="button" class="btn btn-primary text-white" @click="stopBedHeating()">0°</button>     
            </div>
            <div class="col">
                <div class="row justify-content-between">
                    <div class="col">
                        <label for="bed-temp-slider" class="form-label">Bed Heating</label>
                    </div>
                    <div class="col-auto">
                        <input v-model="bed.target_" placeholder="Temp" min="0" max="100" size="3" id="bed-temp-slider" @change="setBedTemp()">
                    </div>
                </div>
                <input type="range" class="form-range" min="0" max="100" step="5" v-model="bed.target_" @change="setBedTemp()">
            </div>
            <div class="col-auto align-self-end">
                <div class="btn-group">
                      <button type="button" class="btn btn-danger text-white me-0" @click="this.bed.target_=60; setBedTemp();">PLA 60°</button>
                      <button type="button" class="btn btn-danger dropdown-toggle dropdown-toggle-split" data-bs-toggle="dropdown" aria-expanded="false">
                            <span class="visually-hidden">Toggle Dropdown</span>
                      </button>
                      <ul class="dropdown-menu">
                            <li><a class="dropdown-item" href="#" @click="this.bed.target_=60; setBedTemp();">PLA 60°</a></li>
                            <li><a class="dropdown-item" href="#" @click="this.bed.target_=75; setBedTemp();">PETG 75°</a></li>
                            <li><a class="dropdown-item" href="#" @click="this.bed.target_=50; setBedTemp();">TPU 50°</a></li>
                            <li><hr class="dropdown-divider"></li>
                            <li><a class="dropdown-item" href="#">Presets</a></li>
                      </ul>
                </div>                                              
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
