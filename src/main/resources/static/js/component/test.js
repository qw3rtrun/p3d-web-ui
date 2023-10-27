import TemperatureControl from "./temperature-control";
import { Person } from "../lib/classes"
export default {
    props : {
        name: String
    },
    emits: ['increased'],

    data() {
        return {
            counter: 0,
        }
    },

    created() {
        console.log("TEST Created");
    },

    mounted() {
        const p = new Person({})
        console.log("TEST Mounted ");
    },

    unmounted() {
        console.log("TEST Unmounted");
    },

    methods: {
        clickHandler() {
            this.counter++;
            this.$emit("increased");
        }
    },

    template: `
    <h3>Hello, {{name}}, {{counter}} times!</h3><button class="btn btn-success" @click="clickHandler">One more</button>    
`
}
