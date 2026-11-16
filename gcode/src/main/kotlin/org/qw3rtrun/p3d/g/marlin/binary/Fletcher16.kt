package org.qw3rtrun.p3d.g.marlin.binary

/**
 * The checksum of the Marlin Binary Protocol Mark II: Fletcher-16 over bytes, modulo 255, both sums
 * starting at zero. Streaming, like `CheckSumCalculator` in the portable core - one byte in at a
 * time, integer state, the value out at the end - so the same code runs over a growing serial
 * buffer and over a whole packet.
 *
 * It is a separate interface from `CheckSumCalculator` on purpose: that one takes a `Char` and
 * answers a `GInt`, because a `*` field covers the *characters* of an ASCII line. This one covers
 * the raw bytes of a binary packet, where a `Char` would be the wrong unit outright.
 *
 * Read off `Marlin/src/feature/binary_stream.h` (bugfix-2.1.x), `BinaryStream::checksum`:
 *
 * ```c
 * uint16_t cs_low = (((cs & 0xFF) + value) % 255);
 * return ((((cs >> 8) + cs_low) % 255) << 8) | cs_low;
 * ```
 *
 * which is the same arithmetic as `_build_checksum` in the reference Python `transfer.py`. The low
 * byte is `sum1`, the high byte `sum2`; the two are kept packed in one `Int` exactly as the
 * firmware keeps them, so there is nothing for a port to get wrong about the ordering.
 *
 * **Modulo 255 means `0x00` and `0xFF` are the same byte to this checksum**, since `(0 + 255) % 255`
 * is `0`. That is not a transcription slip, it is the algorithm the firmware runs, and it has a
 * visible consequence on the wire: a header for sync `0` and a header for sync `255` carry the
 * *identical* checksum, so the two are indistinguishable if the sync byte itself is corrupted.
 * `Fletcher16Test` pins both facts.
 *
 * ```
 * val cs = Fletcher16()
 * cs.add(0x01)
 * cs.add(0x02)
 * cs.get()                                  // 0x0403
 * Fletcher16.of(byteArrayOf(1, 2))          // 0x0403, the same in one call
 * ```
 */
class Fletcher16 {

    private var state = 0

    /**
     * Folds one byte in. Takes an `Int` because every caller here already has one - the packet
     * reader reads `Int` off a stream and the encoder builds its bytes as `Int` - and masks to the
     * low eight bits itself, so a sign-extended `Byte` cannot poison the sums.
     *
     * ```
     * val cs = Fletcher16()
     * cs.add(0xFF)   // and cs.get() is still 0: 255 == 0, modulo 255
     * ```
     *
     * @param value the next byte; only its low eight bits are read
     */
    fun add(value: Int) {
        val low = ((state and 0xff) + (value and 0xff)) % 255
        state = ((((state shr 8) + low) % 255) shl 8) or low
    }

    /**
     * Folds in `bytes[from]` up to but not including `bytes[to]`.
     *
     * ```
     * Fletcher16().add(byteArrayOf(0, 1, 2, 3), 1, 3)   // the same as add(1) then add(2)
     * ```
     *
     * @param bytes the buffer to read from
     * @param from the first index to fold in
     * @param to one past the last index to fold in
     */
    fun add(bytes: ByteArray, from: Int, to: Int) {
        var i = from
        while (i < to) {
            add(bytes[i].toInt())
            i++
        }
    }

    /**
     * The checksum of every byte folded in so far, as the 16-bit value the wire carries
     * little-endian.
     *
     * @return `sum2 shl 8 or sum1`, in `0..0xFEFE`
     */
    fun get(): Int = state

    /** Forgets everything folded in, so one instance can cover one packet after another. */
    fun reset() {
        state = 0
    }

    companion object {

        /**
         * The checksum of a whole range in one call - the form a test or a one-shot caller wants.
         *
         * ```
         * Fletcher16.of(byteArrayOf(0x01, 0x23, 0x00, 0x00))   // 0x6D24, one packet header
         * ```
         *
         * @param bytes the buffer to read
         * @param from the first index to cover
         * @param to one past the last index to cover
         * @return the 16-bit checksum of that range
         */
        fun of(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
            val cs = Fletcher16()
            cs.add(bytes, from, to)
            return cs.get()
        }
    }
}
