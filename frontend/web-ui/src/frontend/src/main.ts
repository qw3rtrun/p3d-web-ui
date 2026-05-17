import {createApp} from 'vue'
import './style.css'
import App from './App.vue'
import {custom} from "./client.ts";

createApp(App).mount('#app')

custom().then(res => {
    let {id: p1, name: p2} = res
    console.log(p1, p2)
})
