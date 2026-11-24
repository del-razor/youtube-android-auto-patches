package app.delrazor.patches.gasbuddy.androidauto

import app.delrazor.patches.gasbuddy.shared.Constants.COMPATIBILITY_GASBUDDY
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceXml

private const val CAR_APP_SERVICE_CLASS =
    "app.delrazor.extension.gasbuddy.androidauto.GasStationsCarAppService"

private const val CAR_APP_DESCRIPTION_NAME = "delrazor_gasbuddy_android_auto_app_desc"

/**
 * Declares the car app in GasBuddy's manifest, the way the Android for Cars App Library documents
 * it for a "point of interest" app:
 *
 * - a CarAppService with the androidx.car.app.category.POI category,
 * - `com.google.android.gms.car.application` meta-data pointing to an XML file that says the app
 *   uses templates,
 * - the lowest car API level the screens need (1),
 * - the permission for map templates.
 */
private val gasBuddyCarAppManifestPatch = patch {
    execute {
        // Written the same way as the YouTube patch's car app description.
        resourceFile("res/xml/$CAR_APP_DESCRIPTION_NAME.xml", false).apply {
            parentFile.mkdirs()
            writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <automotiveApp>
                    <uses name="template" />
                </automotiveApp>
                """.trimIndent()
            )
        }

        resourceXml("AndroidManifest.xml").use { manifest ->
            val alreadyDeclared = manifest.elements("meta-data").any {
                it.attribute("android:name") == "com.google.android.gms.car.application"
            } || manifest.elements("action").any {
                it.attribute("android:name") == "androidx.car.app.CarAppService"
            }
            if (alreadyDeclared) {
                throw PatchException(
                    "This GasBuddy version already has an Android Auto app. " +
                            "The patch must be updated for it."
                )
            }

            val application = manifest.elements("application").first()

            application.parent!!.insertChild(application.index, "uses-permission") {
                setAttribute("android:name", "androidx.car.app.MAP_TEMPLATES")
            }

            application.appendChild("meta-data") {
                setAttribute("android:name", "com.google.android.gms.car.application")
                setAttribute("android:resource", "@xml/$CAR_APP_DESCRIPTION_NAME")
            }
            application.appendChild("meta-data") {
                setAttribute("android:name", "androidx.car.app.minCarApiLevel")
                setAttribute("android:value", "1")
            }

            application.appendChild("service") {
                setAttribute("android:name", CAR_APP_SERVICE_CLASS)
                // The name shown in the Android Auto launcher. The icon is GasBuddy's own.
                setAttribute("android:label", "GasBuddy")
                setAttribute("android:exported", "true")
                appendChild("intent-filter") {
                    appendChild("action") {
                        setAttribute("android:name", "androidx.car.app.CarAppService")
                    }
                    appendChild("category") {
                        setAttribute("android:name", "androidx.car.app.category.POI")
                    }
                }
            }
        }
    }
}

@Suppress("unused")
val gasBuddyAndroidAutoPatch = patch(
    name = "Android Auto gas stations",
    description = "Adds GasBuddy to Android Auto as a car app: a map and list of nearby " +
            "stations with prices, a fuel grade picker, and a Navigate button that hands the " +
            "station to Google Maps or Waze. Android Auto only lists it when the patched app is " +
            "installed with the Play Store as installer, for example with KingInstaller.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GASBUDDY)

    extendWith("extensions/gasbuddy.mpe")

    dependsOn(gasBuddyCarAppManifestPatch)
}
