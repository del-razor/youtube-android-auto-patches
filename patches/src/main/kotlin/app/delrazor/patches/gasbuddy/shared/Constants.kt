package app.delrazor.patches.gasbuddy.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_GASBUDDY = Compatibility(
        name = "GasBuddy",
        // GUESS: GasBuddy's Play Store package name. Check against the APK.
        packageName = "gbis.gbandroid",
        appIconColor = 0x2ECC71,
        targets = listOf(
            // The car app only adds manifest entries and its own classes, so it is not tied to
            // one GasBuddy version. It has not been tested on a device yet.
            AppTarget(
                version = null,
                isExperimental = true
            ),
        )
    )
}
