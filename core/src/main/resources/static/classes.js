/**
 * This is an automatically generated file.
 * Kotlin data classes have been translated into JavaScript classes.
 */
 // noinspection JSUnusedLocalSymbols
/**
 * This class holds methods common to all transpiled classes.
 */
class DataObject {
    // noinspection JSUnusedGlobalSymbols
    /**
     * return {object} This instance as a json object
     */
    asJsonObject() {
        return JSON.parse(JSON.stringify(this));
    }

    // noinspection JSUnusedGlobalSymbols
    /**
     * return {string} This instance as a json string
     */
    asJsonString() {
        return JSON.stringify(this);
    }

    // noinspection JSUnusedGlobalSymbols
    /**
     * return {object} A clone of this object
     */
    clone() {
        return new this.constructor(this.asJsonObject());
    }
}

export class Address extends DataObject {
    constructor(json) {
        super();
        if (json) {
            /**
             * @private
             */
            this.city = json.city;
            /**
             * @private
             */
            this.country = json.country;
            /**
             * @private
             */
            this.streetAddress = json.streetAddress;
            /**
             * @private
             */
            this.zip = json.zip;
        }
    }

    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {string} 
     */
    getCity() {
        return this.city;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {string} 
     */
    getCountry() {
        return this.country;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {string} 
     */
    getStreetAddress() {
        return this.streetAddress;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {string} 
     */
    getZip() {
        return this.zip;
    }
    


}


export class Person extends DataObject {
    constructor(json) {
        super();
        if (json) {
            /**
             * @private
             */
            this.age = parseInt(json.age);
            /**
             * @private
             */
            this.first_name = json.first_name;
            /**
             * @private
             */
            this.healthy = json.healthy;
            /**
             * @private
             */
            this.heightInMeter = parseFloat(json.heightInMeter);
            /**
             * @private
             */
            this.homeAddress = new Address(json.homeAddress);
            /**
             * @private
             */
            this.income = parseInt(json.income);
            /**
             * @private
             */
            this.lastEdited = new Date(json.lastEdited);
            /**
             * @private
             */
            this.last_name = json.last_name;
            /**
             * @private
             */
            this.luckyNumbers = json.luckyNumbers;
            /**
             * @private
             */
            this.nicknames = json.nicknames;
            /**
             * @private
             */
            this.pets = json.pets.map(x => new Pet(x));
            /**
             * @private
             */
            this.traits = json.traits.map(x => Trait[x].name);
        }
    }

    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {number} 
     */
    getAge() {
        return this.age;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {string} 
     */
    getFirstName() {
        return this.first_name;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {boolean} 
     */
    getHealthy() {
        return this.healthy;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {number} 
     */
    getHeightInMeter() {
        return this.heightInMeter;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {Address} 
     */
    getHomeAddress() {
        return this.homeAddress;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {number} 
     */
    getIncome() {
        return this.income;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {Date} 
     */
    getLastEdited() {
        return this.lastEdited;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {string} 
     */
    getLastName() {
        return this.last_name;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {int[]} A copy of the array held
     */
    getLuckyNumbers() {
        return this.luckyNumbers.slice();
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {string[]} A copy of the array held
     */
    getNicknames() {
        return this.nicknames.slice();
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {Pet[]} A copy of the array held
     */
    getPets() {
        return this.pets.slice();
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {Trait[]} A copy of the array held
     */
    getTraits() {
        return this.traits.slice();
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * 
     * @param {string} firstName
     * @return {Person}
     */
    setFirstName(firstName) {
        this.first_name = firstName;
        return this;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * 
     * @param {Date} lastEdited
     * @return {Person}
     */
    setLastEdited(lastEdited) {
        this.lastEdited = lastEdited;
        return this;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * 
     * @param {string} lastName
     * @return {Person}
     */
    setLastName(lastName) {
        this.last_name = lastName;
        return this;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * Argument array is copied and set
     * @param {int[]} luckyNumbers
     * @return {Person}
     */
    setLuckyNumbers(luckyNumbers) {
        this.luckyNumbers = luckyNumbers.slice();
        return this;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * Argument array is copied and set
     * @param {string[]} nicknames
     * @return {Person}
     */
    setNicknames(nicknames) {
        this.nicknames = nicknames.slice();
        return this;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * Argument array is copied and set
     * @param {Pet[]} pets
     * @return {Person}
     */
    setPets(pets) {
        this.pets = pets.slice();
        return this;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * Argument array is copied and set
     * @param {Trait[]} traits
     * @return {Person}
     */
    setTraits(traits) {
        this.traits = traits.slice();
        return this;
    }
    

}


export class Pet extends DataObject {
    constructor(json) {
        super();
        if (json) {
            /**
             * @private
             */
            this.name = json.name;
            /**
             * @private
             */
            this.theSpecies = json.theSpecies;
        }
    }

    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {string} 
     */
    getName() {
        return this.name;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * @return {Species} 
     */
    getTheSpecies() {
        return Species[this.theSpecies];
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * 
     * @param {string} name
     * @return {Pet}
     */
    setName(name) {
        this.name = name;
        return this;
    }
    
    // noinspection JSUnusedGlobalSymbols
    /**
     * 
     * @param {Species} theSpecies
     * @return {Pet}
     */
    setTheSpecies(theSpecies) {
        this.theSpecies = theSpecies.name;
        return this;
    }
    

}

/**
 * @typedef {{name: string, lifeSpan: number, alignment: string}} Species
 */
export const Species = Object.freeze({
    CAT: {name: 'CAT', lifeSpan: 16, alignment: 'Chaotic Evil'},
    DOG: {name: 'DOG', lifeSpan: 13, alignment: 'Neutral Good'}
});


/**
 * @typedef {{name: string}} Trait
 */
export const Trait = Object.freeze({
    HUMBLE: {name: 'HUMBLE'},
    HONORABLE: {name: 'HONORABLE'},
    DILIGENT: {name: 'DILIGENT'},
    LOYAL: {name: 'LOYAL'},
    KIND: {name: 'KIND'}
});

