package app.delrazor.patches.youtube.androidauto

import app.delrazor.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml

private const val MEDIA_SERVICE_CLASS =
    "app.delrazor.extension.youtube.androidauto.AndroidAutoMediaService"

/**
 * Android Auto lists a media (audio) app when it has a service that answers the
 * `android.media.browse.MediaBrowserService` intent and its car app description
 * declares `<uses name="media"/>`.
 */
private val androidAutoAudioResourcePatch = patch {
    dependsOn(carAppSupportPatch)

    execute {
        addCarAppUse("media")

        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.elements("application").first().appendChild("service") {
                setAttribute("android:name", MEDIA_SERVICE_CLASS)
                // Android Auto runs in another app, so the service must be exported.
                // The service itself only answers callers it trusts (see the extension).
                setAttribute("android:exported", "true")
                appendChild("intent-filter") {
                    appendChild("action") {
                        setAttribute("android:name", MEDIA_BROWSER_ACTION)
                    }
                }
            }
        }
    }
}

@Suppress("unused")
val androidAutoPatch = patch(
    name = "Android Auto",
    description = "Adds an audio entry for YouTube to Android Auto. What YouTube plays in the " +
            "background appears on the car screen with play, pause, skip and seek controls. " +
            "Needs the background playback patch, and Android Auto's 'Unknown sources' " +
            "developer setting unless the app was installed by the Play Store.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        carAppSupportPatch,
        mediaSessionHookPatch,
        androidAutoAudioResourcePatch,
    )
}
