package app.fieldwatch.domain

/**
 * OpenDroneID NAN service-specific information: counter, then an ASTM message or pack.
 * The discovery service name is the filter; SSI has no BLE app code or Wi-Fi vendor OUI.
 * Framing verified against opendroneid-core-c odid_wifi.h/wifi.c and receiver-android
 * OpenDroneIdDataManager.receiveDataNaN (parse offset 1). This is an independent parser,
 * not copied upstream code. Only protocol 2, the pinned reference encoder's version,
 * is decoded. Unknown versions remain unsupported rather than being guessed.
 * Sources: https://github.com/opendroneid/opendroneid-core-c/blob/master/libopendroneid/odid_wifi.h
 * https://github.com/opendroneid/receiver-android/blob/master/Android/app/src/main/java/org/opendroneid/android/bluetooth/OpenDroneIdDataManager.java
 */
object OpenDroneIdNan {
    private const val MESSAGE_SIZE = 25
    private const val MAX_MESSAGES = 9
    private const val VERSION = 2
    private const val MAX_SSI_SIZE = 1 + 3 + MAX_MESSAGES * MESSAGE_SIZE

    fun messages(serviceSpecificInfo: ByteArray?): List<ByteArray> {
        val bytes = serviceSpecificInfo ?: return emptyList()
        if (bytes.size !in 1 + MESSAGE_SIZE..MAX_SSI_SIZE) return emptyList()
        val header = bytes[1].toInt() and 0xFF
        if ((header and 0x0F) != VERSION) return emptyList()
        if ((header shr 4) != 0x0F) {
            if (bytes.size != 1 + MESSAGE_SIZE || !validMessageHeader(header)) return emptyList()
            return listOf(bytes.copyOfRange(1, bytes.size))
        }
        if (bytes.size < 4 || (bytes[2].toInt() and 0xFF) != MESSAGE_SIZE) return emptyList()
        val count = bytes[3].toInt() and 0xFF
        if (count !in 1..MAX_MESSAGES || bytes.size != 4 + count * MESSAGE_SIZE) return emptyList()
        // Reject the entire truncated/unsupported pack; never emit a misleading partial decode.
        for (i in 0 until count) {
            if (!validMessageHeader(bytes[4 + i * MESSAGE_SIZE].toInt() and 0xFF)) return emptyList()
        }
        val messages = (0 until count).map { i ->
            val offset = 4 + i * MESSAGE_SIZE
            bytes.copyOfRange(offset, offset + MESSAGE_SIZE)
        }
        return messages.takeIf(OpenDroneId::validPackContent) ?: emptyList()
    }

    internal fun messagesHex(dataHex: String): List<ByteArray> {
        if (dataHex.length % 2 != 0 || dataHex.length > MAX_SSI_SIZE * 2 ||
            dataHex.any { it !in '0'..'9' && it !in 'A'..'F' && it !in 'a'..'f' }) return emptyList()
        val bytes = ByteArray(dataHex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = dataHex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte()
                ?: return emptyList()
        }
        return messages(bytes)
    }

    private fun validMessageHeader(header: Int): Boolean =
        (header and 0x0F) == VERSION && (header shr 4) in 0..5
}
