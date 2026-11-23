package app.delrazor.patches.youtube.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_YOUTUBE = Compatibility(
        name = "YouTube",
        packageName = "com.google.android.youtube",
        // Same file type the official Morphe YouTube patches declare.
        apkFileType = ApkFileType.APK_REQUIRED,
        appIconColor = 0xFF0033,
        targets = listOf(
            // The Android Auto patch only hooks Android framework calls
            // (android.media.session.MediaSession), so it is not tied to one YouTube version.
            // It has not been tested on a device yet, so every target is experimental.
            AppTarget(
                version = null,
                isExperimental = true
            ),
            // Version the official Morphe patches currently recommend. Patch this one first.
            AppTarget(
                version = "21.30.216",
                minSdk = 29,
                isExperimental = true
            ),
        )
    )
}
