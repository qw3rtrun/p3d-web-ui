import {watch, ref} from 'vue';

export default {
    props: {
        name: String,
        current: Number,
        value: Number,
        power: Number,
        presets: Object
    },

    emits: {
        change: val => {
            return val >= 0;
        },
    },

    // data() {
    //     return {
    //         target: 0,
    //         input: 0,
    //         presetIndex: 0
    //     }
    // },
    computed: {
        activePreset() {
            return this.presets[this.presetIndex];
        },
        heating() {
            return this.current > 0;
        }
    },

    setup(props, data) {
        const target = ref(0);
        const input = ref(0);
        const presetIndex = ref(0);
        watch(() => props.value, (n) =>  {
            console.log("New target temp changed :" + n);
            input.value = n;
        });
        return {target, input, presetIndex};
    },

    methods: {
        setHeatingTemp() {
            this.$emit('change', this.input)
        },
        stopHeating() {
            this.value = 0;
            this.$emit('change', 0)
        },
        activatePreset(index) {
            this.presetIndex = index;
            const preset = this.presets[index];
            this.input = preset.value;
            this.setHeatingTemp();
        }
    },
    template: `
        <div class="row pt-2">
            <div class="col-3 align-self-center">
                <h4 class="card-title">{{current}} °C <span v-if="heating"> -> {{value}} °C</span></h4>
            </div>
            <div class="col-auto align-self-end">
                <button type="button" class="btn btn-primary text-white" @click="stopHeating()">OFF</button>     
            </div>
            <div class="col">
                <div class="row justify-content-between">
                    <div class="col">
                        <label for="h1-temp-slider" class="form-label">{{name}} Heating</label>
                    </div>
                    <div class="col-auto">
                        <input v-model="input" placeholder="Temp" min="0" max="350" size="3" id="h1-temp-slider" @change="setHeatingTemp()">
                    </div>
                </div>
                <input type="range" class="form-range" min="0" max="350" step="5" v-model="input" @change="setHeatingTemp()">
            </div>
            <div class="col-auto align-self-end">
                <div class="btn-group">
                      <button type="button" class="btn btn-danger text-white me-0" @click="activatePreset(presetIndex)">{{activePreset.label}} {{activePreset.value}}°</button>
                      <button type="button" class="btn btn-danger dropdown-toggle dropdown-toggle-split" data-bs-toggle="dropdown" aria-expanded="false">
                            <span class="visually-hidden">Toggle Dropdown</span>
                      </button>
                      <ul class="dropdown-menu">
                            <li v-for="(ps, index) in presets">
                                <a class="dropdown-item" href="#" @click="activatePreset(index);">{{ps.label}} {{ps.value}}°</a>
                            </li>
                            <li><hr class="dropdown-divider"></li>
                            <li><a class="dropdown-item" href="#">Presets</a></li>
                      </ul>
                </div>                                              
            </div>
        </div>
`
}
