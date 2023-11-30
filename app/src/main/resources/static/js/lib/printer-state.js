import {shallowRef, triggerRef} from "vue"
import client from "./client.js"

export class TemperatureState {
    constructor(current, target, power) {
        this.current = current;
        this.target = target;
        this.power = power;
    }

    onTemperatureChanged(state) {
        this.current = state.current;
        this.target = state.target;
        this.power = state.power;
    }
}

export class TemperatureControl {
    constructor(hotend, bed) {
        this.hotend = hotend;
        this.bed = bed;
    }

    onTemperatureReport(report) {
        this.hotend.onTemperatureChanged(report.hotend);
        this.bed.onTemperatureChanged(report.bed);
    }
}

export class PrinterState {
    constructor(uuid, name) {
        this.uuid = uuid;
        this.name = name;
        this.temperature = shallowRef(new TemperatureControl(
            new TemperatureState(),
            new TemperatureState()
        ));
        this.client = client.api(uuid);
        this.cb = payload => {
            if (payload.type === "OkTemperatureReported" || payload.type === "TemperatureReported") {
                const report = payload.event;
                this.temperature.value.onTemperatureReport(report);
                triggerRef(this.temperature);
            }
        }
        client.subscribe(uuid, this.cb);
    }
}
