package app.fieldwatch.radio

/** Fixed boundaries survive DeviceStore's delimiter-stripping address normalization. */
internal object WifiNanPeerIdentity {
    fun address(receiverId: String, generation: Long, peer: Long): String =
        "NAN:$receiverId:${hex(generation)}:${hex(peer)}"

    private fun hex(value: Long): String = java.lang.Long.toHexString(value).padStart(16, '0')
}
