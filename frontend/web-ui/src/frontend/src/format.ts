import type {SliceInfo} from './api/types'

/**
 * Numbers from the server, turned into something a person reads.
 *
 * **Pure functions in a module rather than expressions in a template**, because this is the only
 * logic in the UI that can be wrong without rendering wrong: `3661` and `1h 1m` are the same fact,
 * and a bad conversion looks exactly as plausible as a good one. Nothing here touches Vue, a DOM
 * node or the clock, so every one of them is a call with an expected string beside it the moment
 * there is a runner to put it in - see the known debt in the UI skill: `vue-tsc` checks types and
 * nothing checks behaviour.
 *
 * **Null in, null out.** The primitives return null for "nobody said" rather than a placeholder,
 * so the sentence a page shows for an unknown value is the page's choice and is made in exactly
 * one place - {@link NOT_STATED} below.
 */

/**
 * What a field says when the file did not say it.
 *
 * Not `0`, which would be a claim - a print that takes no time and uses no filament - and not an
 * error, because a file nothing could be read from is still a printable file.
 */
export const NOT_STATED = 'not stated'

/**
 * Seconds as a human duration: `3661` is `1h 1m`.
 *
 * Coarse on purpose and deliberately asymmetric. A print measured in hours does not want its
 * seconds, and an eight-hour one does not want its minutes to the unit, so each magnitude drops
 * the one below the one below it and a zero remainder is left out entirely - `7200` is `2h`, not
 * `2h 0m`. Days exist because multi-day prints do.
 *
 * Returns null for null, and for anything that is not a usable count of seconds. A negative
 * estimate is not a duration, and a slicer that wrote one is a slicer whose arithmetic nothing
 * here should be repeating back to an operator as fact.
 */
export function duration(seconds: number | null): string | null {
    if (seconds === null || !Number.isFinite(seconds) || seconds < 0) {
        return null
    }
    if (seconds < 60) {
        return 'under a minute'
    }

    const minutes = Math.floor(seconds / 60)
    if (minutes < 60) {
        return `${minutes}m`
    }

    const hours = Math.floor(minutes / 60)
    if (hours < 24) {
        const rest = minutes % 60
        return rest === 0 ? `${hours}h` : `${hours}h ${rest}m`
    }

    const days = Math.floor(hours / 24)
    const rest = hours % 24
    return rest === 0 ? `${days}d` : `${days}d ${rest}h`
}

/**
 * Filament length as a human measure: `1234.5` millimetres is `1.23 m`.
 *
 * **The server's unit is millimetres and nobody thinks in them.** A spool is sold by the kilometre
 * of filament and a slicer prints metres on its own summary, so a figure over a metre is shown in
 * metres to two places; below that, whole millimetres, because `0.45 m` for a benchmark cube reads
 * as a rounding error rather than as a length.
 *
 * Null and nonsense are null, as in {@link duration}.
 */
export function filament(millimetres: number | null): string | null {
    if (millimetres === null || !Number.isFinite(millimetres) || millimetres < 0) {
        return null
    }
    if (millimetres < 1000) {
        return `${Math.round(millimetres)} mm`
    }
    return `${(millimetres / 1000).toFixed(2)} m`
}

/** A byte count in the largest unit that leaves a number worth reading. */
export function fileSize(bytes: number): string {
    if (bytes < 1024) {
        return `${bytes} B`
    }
    if (bytes < 1024 * 1024) {
        return `${(bytes / 1024).toFixed(1)} KB`
    }
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** How long the slicer thinks the print takes, or {@link NOT_STATED}. */
export function estimate(slice: SliceInfo): string {
    return duration(slice.estimatedSeconds) ?? NOT_STATED
}

/** How much filament the slicer thinks the print uses, or {@link NOT_STATED}. */
export function filamentUsed(slice: SliceInfo): string {
    return filament(slice.filamentMillimetres) ?? NOT_STATED
}

/**
 * The rest of what the slicer said, as one line for a tooltip.
 *
 * The estimate and the filament are what earn a column; **the program's name and the layer height
 * are detail**, true but not what anybody scans a list for. They go where a hover finds them.
 *
 * `known` is read here and nowhere else, and this is what it is for: a file whose comments said
 * nothing gets a sentence explaining that the host could not read a summary, rather than a blank
 * that looks like a bug or three repetitions of {@link NOT_STATED}.
 */
export function sliceDetail(slice: SliceInfo): string {
    if (!slice.known) {
        return 'No slicer summary could be read from this file. It is still printable.'
    }

    const parts: string[] = []
    if (slice.slicer !== null) {
        parts.push(slice.slicer)
    }
    if (slice.layerHeight !== null) {
        parts.push(`${slice.layerHeight} mm layers`)
    }
    return parts.length === 0 ? 'The slicer did not name itself.' : parts.join(' · ')
}
