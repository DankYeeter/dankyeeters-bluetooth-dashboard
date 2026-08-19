package dev.dankyeeter.btdashboard.monitor.effects

/**
 * Headphone companion apps whose equaliser runs *inside the headphones*.
 *
 * This is the blind spot of every Android-side effect scan, including ours.
 * Apps like Focal & Co or Sony Headphones Connect hold no audio session and
 * register no `AudioEffect`: they send vendor commands over Bluetooth and the
 * DSP in the headphone does the filtering. `dumpsys media.audio_flinger`
 * therefore reports zero effect chains on a device whose bass is being shelved
 * by 6 dB — verified on a Focal Bathys, where the companion app holds only
 * BLUETOOTH_CONNECT and RECORD_AUDIO and never appears in the audio dump.
 *
 * That EQ still stacks with ours, so silence would be the dishonest answer.
 * We cannot read it, but we can tell that the app which controls it is
 * installed, and say exactly what the user has to check by hand.
 */
data class VendorEqApp(
    val packageName: String,
    val appLabel: String,
    val vendor: String,
)

object VendorEqApps {

    val known: List<VendorEqApp> = listOf(
        VendorEqApp("com.naimaudio.naim.std", "Focal & Co", "Focal"),
        VendorEqApp("com.sony.songpal.mdr", "Sony | Headphones Connect", "Sony"),
        VendorEqApp("com.bose.monet", "Bose Connect", "Bose"),
        VendorEqApp("com.bose.bosemusic", "Bose Music", "Bose"),
        VendorEqApp("com.sennheiser.control", "Sennheiser Smart Control", "Sennheiser"),
        VendorEqApp("com.sennheiser.smartcontrol", "Sennheiser Smart Control", "Sennheiser"),
        VendorEqApp("com.jbl.headphones", "JBL Headphones", "JBL"),
        VendorEqApp("com.beoplay.bang_olufsen", "Bang & Olufsen", "Bang & Olufsen"),
        VendorEqApp("com.anker.soundcore", "Soundcore", "Soundcore"),
        VendorEqApp("com.libratone.androidapp", "Libratone", "Libratone"),
        VendorEqApp("com.nothing.smartcenter", "Nothing X", "Nothing"),
        VendorEqApp("com.jabra.sound", "Jabra Sound+", "Jabra"),
    )

    fun byPackage(packageName: String): VendorEqApp? =
        known.firstOrNull { it.packageName == packageName }
}
