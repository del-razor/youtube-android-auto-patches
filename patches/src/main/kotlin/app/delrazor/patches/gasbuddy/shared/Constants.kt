package app.delrazor.patches.gasbuddy.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_GASBUDDY = Compatibility(
        name = "GasBuddy",
        packageName = "gbis.gbandroid",
        appIconColor = 0x2ECC71,
        targets = listOf(
            // The patch hooks React Native's NetworkingModule and reads GasBuddy's station
            // search reply, so other versions should work while the reply keeps its shape.
            AppTarget(
                version = null,
                isExperimental = true
            ),
            // The version the patch was written against. Not tested on a device yet.
            AppTarget(
                version = "702.10.27044",
                minSdk = 26,
                isExperimental = true
            ),
        )
    )
}
