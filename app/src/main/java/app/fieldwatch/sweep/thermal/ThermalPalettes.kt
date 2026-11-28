package app.fieldwatch.sweep.thermal

internal object ThermalPalettes {
    val whiteHot = IntArray(256) { 0xff000000.toInt() or (it * 0x010101) }
    val ironbow = ramp(intArrayOf(0x070515, 0x422066, 0x982655, 0xdc4932, 0xffa331, 0xfff4af))
    val spotlight = ramp(intArrayOf(0xffab32, 0xff6856, 0xfffdf6))
    private fun ramp(stops: IntArray) = IntArray(256) { index ->
        val position = index * (stops.size - 1) / 255f
        val lower = position.toInt()
        val a = stops[lower]
        val b = stops[minOf(lower + 1, stops.lastIndex)]
        val t = position - lower
        fun channel(shift: Int): Int {
            val start = a shr shift and 255
            return (start + ((b shr shift and 255) - start) * t).toInt() shl shift
        }
        0xff000000.toInt() or channel(16) or channel(8) or channel(0)
    }
}
