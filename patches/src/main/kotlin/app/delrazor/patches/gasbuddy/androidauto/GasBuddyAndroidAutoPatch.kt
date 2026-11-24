package app.delrazor.patches.gasbuddy.androidauto

import app.delrazor.patches.gasbuddy.shared.Constants.COMPATIBILITY_GASBUDDY
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import java.util.logging.Logger

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

private const val REQUEST_TAP_CLASS =
    "Lapp/delrazor/extension/gasbuddy/androidauto/GasBuddyRequestTap;"

private const val NETWORKING_MODULE_CLASS = "Lcom/facebook/react/modules/network/NetworkingModule;"

/**
 * GasBuddy is a React Native app, so its station search is JavaScript that sends requests through
 * React Native's NetworkingModule. Every request passes NetworkingModule.sendRequestInternalReal(
 * method, url, requestId, headers, ...), and React Native keeps that class and method name in
 * release builds. Before each of those calls, the method, address, id and headers are handed to
 * GasBuddyRequestTap, which remembers the last station search so the car screen can repeat it
 * for the car's position.
 */
private val gasBuddyRequestTapPatch = patch {
    extendWith("extensions/gasbuddy.mpe")

    execute {
        var hookCount = 0

        methodCall(
            definingClass = NETWORKING_MODULE_CLASS,
            name = "sendRequestInternalReal",
        ).matchAllMethodIndicesForEach(requireMatches = false) { index ->
            if (definingClass.startsWith("Lapp/delrazor/")) return@matchAllMethodIndicesForEach

            // An instance call: this, method, url, requestId, headers, then the rest.
            val tapCall = when (val instruction = getInstruction<Instruction>(index)) {
                is RegisterRangeInstruction -> {
                    val first = instruction.startRegister + 1
                    "invoke-static/range { v$first .. v${first + 3} }"
                }
                is FiveRegisterInstruction -> {
                    val registers = listOf(
                        instruction.registerC, instruction.registerD, instruction.registerE,
                        instruction.registerF, instruction.registerG,
                    ).take(instruction.registerCount)
                    if (registers.size < 5) throw PatchException("Unexpected request call: $instruction")
                    "invoke-static { ${registers.subList(1, 5).joinToString { "v$it" }} }"
                }
                else -> throw PatchException("Unexpected request call: $instruction")
            }

            addInstruction(
                index,
                "$tapCall, $REQUEST_TAP_CLASS->onRequest(" +
                        "Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)V"
            )
            hookCount++
        }

        if (hookCount == 0) {
            throw PatchException(
                "Could not find where GasBuddy sends its requests (React Native NetworkingModule)"
            )
        }
        Logger.getLogger(this::class.java.name).info(
            "Android Auto: watching $hookCount GasBuddy request path(s) for the station search"
        )
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

    dependsOn(gasBuddyCarAppManifestPatch, gasBuddyRequestTapPatch)
}
