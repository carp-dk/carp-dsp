package carp.dsp.core.application.authoring.mapper

import dk.cachet.carp.common.application.UUID

/**
 * Deterministic UUID v5 generation using SHA-1 hashing.
 * Same namespace + name always produces the same UUID.
 *
 * RFC 4122 Section 4.3: SHA-1 based UUID generation.
 */
object DeterministicUUID {

    // ── RFC 4122 bit-manipulation constants ───────────────────────────────────

    /** Mask to clear the upper nibble of the version byte (bits 7–4). */
    private const val VERSION_CLEAR_MASK = 0x0f

    /** Version 5 flag ORed into the version byte (bits 7–4 = 0101). */
    private const val VERSION_5_FLAG = 0x50

    /** Mask to clear the upper two bits of the variant byte. */
    private const val VARIANT_CLEAR_MASK = 0x3f

    /** RFC 4122 variant bits ORed into the variant byte (bits 7–6 = 10). */
    private const val VARIANT_RFC_FLAG = 0x80

    /** Byte index of the version nibble in the SHA-1 digest. */
    private const val VERSION_BYTE_INDEX = 6

    /** Byte index of the variant nibble in the SHA-1 digest. */
    private const val VARIANT_BYTE_INDEX = 8

    // ── UUID string formatting constants ──────────────────────────────────────

    /** Number of hex characters per byte. */
    private const val HEX_CHARS_PER_BYTE = 2

    /** Substring end index for the first UUID segment (8 hex chars). */
    private const val SEG1_END = 8

    /** Substring end index for the second UUID segment (4 hex chars). */
    private const val SEG2_END = 12

    /** Substring end index for the third UUID segment (4 hex chars, version). */
    private const val SEG3_END = 16

    /** Substring end index for the fourth UUID segment (4 hex chars, variant). */
    private const val SEG4_END = 20

    /** Substring end index for the fifth UUID segment (12 hex chars). */
    private const val SEG5_END = 32

    /** Radix used for parsing hexadecimal strings. */
    private const val HEX_RADIX = 16

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates a deterministic UUID v5 using SHA-1 hashing.
     *
     * @param namespace The namespace UUID (e.g., workflow ID)
     * @param name The local identifier within that namespace (e.g., "step-001")
     * @return Deterministic UUID v5
     */
    fun v5(namespace: UUID, name: String): UUID {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val namespaceBytes = hexStringToBytes(namespace.stringRepresentation.replace("-", ""))

        val data = namespaceBytes + nameBytes
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(data)

        // Set version (5) and variant bits per RFC 4122
        val versionBits = digest[VERSION_BYTE_INDEX].toInt() and VERSION_CLEAR_MASK or VERSION_5_FLAG
        digest[VERSION_BYTE_INDEX] = versionBits.toByte()

        val variantBits = digest[VARIANT_BYTE_INDEX].toInt() and VARIANT_CLEAR_MASK or VARIANT_RFC_FLAG
        digest[VARIANT_BYTE_INDEX] = variantBits.toByte()

        // Format as UUID string
        val hex = digest.joinToString("") { "%02x".format(it) }
        val uuidString = hex.substring( 0, SEG1_END ) + "-" +
                         hex.substring( SEG1_END, SEG2_END ) + "-" +
                         hex.substring( SEG2_END, SEG3_END ) + "-" +
                         hex.substring( SEG3_END, SEG4_END ) + "-" +
                         hex.substring( SEG4_END, SEG5_END )

        return UUID(uuidString)
    }

    private fun hexStringToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / HEX_CHARS_PER_BYTE)
        for (i in bytes.indices) {
            val start = i * HEX_CHARS_PER_BYTE
            bytes[i] = hex.substring( start, start + HEX_CHARS_PER_BYTE ).toInt( HEX_RADIX ).toByte()
        }
        return bytes
    }
}
