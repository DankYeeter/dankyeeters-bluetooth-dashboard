package dev.dankyeeter.btdashboard.monitor.codec

/**
 * Whether two Bluetooth addresses name the same device.
 *
 * User builds redact addresses in `dumpsys` output to `XX:XX:XX:XX:35:6a`,
 * while the A2DP profile hands out the real one. A plain string compare
 * therefore never matches across those two sources, so every consumer that
 * joins them needs this. The last two octets are printed verbatim and are
 * enough to tell apart the handful of devices one phone connects at once.
 */
fun sameDevice(a: String, b: String): Boolean {
    if (a.equals(b, ignoreCase = true)) return true
    val tailA = a.takeLast(5)
    val tailB = b.takeLast(5)
    return tailA.length == 5 && tailA.contains(':') && tailA.equals(tailB, ignoreCase = true)
}
