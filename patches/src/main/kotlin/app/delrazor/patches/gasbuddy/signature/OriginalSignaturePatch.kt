package app.delrazor.patches.gasbuddy.signature

import app.delrazor.patches.gasbuddy.shared.Constants.COMPATIBILITY_GASBUDDY
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml

private const val PROVIDER_CLASS =
    "app.delrazor.extension.gasbuddy.signature.OriginalSignatureProvider"

/**
 * Registers the provider that puts the original signature in place at process start. Content
 * providers start before Application.onCreate, highest initOrder first.
 */
private val originalSignatureManifestPatch = patch {
    execute {
        resourceXml("AndroidManifest.xml").use { manifest ->
            val application = manifest.elements("application").first()
            // Same as the Shizuku provider in the YouTube patch: read the package here, so the
            // authority stays unique if the package is renamed.
            val packageName = application.parent!!.attribute("package")!!
            application.appendChild("provider") {
                setAttribute("android:name", PROVIDER_CLASS)
                setAttribute("android:authorities", "$packageName.delrazor.originalsignature")
                setAttribute("android:exported", "false")
                setAttribute("android:initOrder", "999999")
            }
        }
    }
}

@Suppress("unused")
val gasBuddyOriginalSignaturePatch = patch(
    name = "Fix blank Google Maps",
    description = "Patching re-signs GasBuddy, and Google Maps then refuses GasBuddy's map key, " +
            "so the map stays blank. This makes GasBuddy report its original Play Store " +
            "certificate to the Google code running inside it, so the map loads again.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GASBUDDY)

    extendWith("extensions/gasbuddy.mpe")

    dependsOn(originalSignatureManifestPatch)
}
