export interface CustomData {
    id: number,
    name: string
}

const defaultData: CustomData = {id: -1, name: "Sorry =("}

function get<R>(url: string): Promise<R> {
    const requestOptions = {
        method: "GET",
        headers: {"Content-Type": "application/json"},
    };

    // Simple POST request with a JSON body using fetch
    return fetch(url, requestOptions)
        .then(response => response.json())
        .then<R, R>(json => {
            console.log(`${json}`);
            return json as R;
        }, err => {
            console.log(`${url} -> Failed to fetch ${err}`);
            return defaultData as R;
        })
}

export function custom(): Promise<CustomData> {
    return get(`/custom`)
}
