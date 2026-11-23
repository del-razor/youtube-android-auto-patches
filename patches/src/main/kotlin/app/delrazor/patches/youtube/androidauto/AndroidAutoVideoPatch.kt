package app.delrazor.patches.youtube.androidauto

import app.delrazor.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml

private const val CAR_VIDEO_SERVICE_CLASS =
    "app.delrazor.extension.youtube.androidauto.CarVideoService"

/**
 * Adds a "projection" entry for Android Auto: a service that the unofficial Android Auto SDK
 * (bundled in the extension) uses to draw a full screen on the car display.
 */
private val androidAutoVideoResourcePatch = patch {
    dependsOn(carAppSupportPatch)

    execute {
        // Same entries Fermata Auto declares for its car screen.
        addCarAppUse("service")
        addCarAppUse("projection")

        resourceXml("AndroidManifest.xml").use { manifest ->
            val application = manifest.elements("application").first()

            application.appendChild("service") {
                setAttribute("android:name", CAR_VIDEO_SERVICE_CLASS)
                // The name shown in the Android Auto launcher.
                setAttribute("android:label", "YouTube")
                setAttribute("android:exported", "true")
                appendChild("intent-filter") {
                    appendChild("action") {
                        setAttribute("android:name", "android.intent.action.MAIN")
                    }
                    appendChild("category") {
                        setAttribute("android:name", "com.google.android.gms.car.category.CATEGORY_PROJECTION")
                    }
                    appendChild("category") {
                        setAttribute("android:name", "com.google.android.gms.car.category.CATEGORY_PROJECTION_OEM")
                    }
                }
            }
        }
    }
}

@Suppress("unused")
val androidAutoVideoPatch = patch(
    name = "Android Auto video",
    description = "Adds YouTube to Android Auto as a full app on the car screen: home, " +
            "search, the player and its controls, used from the car's touchscreen. Video " +
            "follows the car's own 'video allowed' signal. Android Auto " +
            "only lists it when the patched app is installed with the Play Store as installer, " +
            "for example with KingInstaller.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        carAppSupportPatch,
        mediaSessionHookPatch,
        androidAutoVideoResourcePatch,
    )
}
