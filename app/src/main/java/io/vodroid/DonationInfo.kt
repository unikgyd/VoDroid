package io.vodroid

/** Public donation destinations verified against unikgyd/VoWin's README and AboutViewModel. */
object DonationInfo {
    const val SOURCE_URL="https://github.com/unikgyd/VoWin#%E8%B5%9E%E5%8A%A9%E6%94%AF%E6%8C%81"
    data class Destination(val id:String,val chain:String,val network:String,val address:String)
    val destinations=listOf(
        Destination("tron","TRON","TRC-20","TDecoS5mFozuyfJSR2QhSsWbCkGm8nsvpH"),
        Destination("bsc","BNB Smart Chain","BEP-20","0x0e5e07b7604d8585fd936527f48874a10ad8b884"),
        Destination("polygon","Polygon","Polygon 网络","0x0e5e07b7604d8585fd936527f48874a10ad8b884")
    )
}
