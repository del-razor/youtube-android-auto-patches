package app.delrazor.patches.youtube.androidauto

import app.delrazor.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
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

internal const val EXTENSION_PACKAGE = "Lapp/delrazor/extension/"

private const val BRIDGE_CLASS =
    "Lapp/delrazor/extension/youtube/androidauto/AndroidAutoBridge;"

private const val MEDIA_SERVICE_CLASS =
    "app.delrazor.extension.youtube.androidauto.AndroidAutoMediaService"

internal const val CAR_APP_DESCRIPTION_NAME = "delrazor_android_auto_app_desc"

/**
 * Android Auto lists a media app when its manifest has:
 *
 * 1. a service that answers the `android.media.browse.MediaBrowserService` intent, and
 * 2. `com.google.android.gms.car.application` meta-data pointing to an XML file
 *    that declares `<uses name="media"/>`.
 *
 * YouTube has neither, so this resource step adds both.
 */
internal val androidAutoResourcePatch = patch {
    execute {
        // The car app description file. Written as a new file in res/xml.
        // UNTESTED: assumes the patcher compiles a new res/xml file written this way,
        // the same way the official patches' copyResources() helper adds files.
        resourceFile("res/xml/$CAR_APP_DESCRIPTION_NAME.xml", false).apply {
            parentFile.mkdirs()
            writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <automotiveApp>
                    <uses name="media" />
                </automotiveApp>
                """.trimIndent()
            )
        }

        resourceXml("AndroidManifest.xml").use { manifest ->
            val alreadyDeclared = manifest.elements("meta-data").any {
                it.attribute("android:name") == "com.google.android.gms.car.application"
            }
            if (alreadyDeclared) {
                throw PatchException(
                    "This YouTube version already declares Android Auto support. " +
                            "This patch is not needed, or YouTube changed and the patch must be updated."
                )
            }

            val application = manifest.elements("application").first()

            application.appendChild("meta-data") {
                setAttribute("android:name", "com.google.android.gms.car.application")
                setAttribute("android:resource", "@xml/$CAR_APP_DESCRIPTION_NAME")
            }

            application.appendChild("service") {
                setAttribute("android:name", MEDIA_SERVICE_CLASS)
                // Android Auto runs in another app, so the service must be exported.
                // The service itself only answers callers it trusts (see the extension).
                setAttribute("android:exported", "true")
                appendChild("intent-filter") {
                    appendChild("action") {
                        setAttribute("android:name", "android.media.browse.MediaBrowserService")
                    }
                }
            }
        }
    }
}

@Suppress("unused")
val androidAutoPatch = patch(
    name = "Android Auto",
    description = "Shows YouTube in Android Auto. What YouTube plays in the background appears " +
            "on the car screen with play, pause, skip and seek controls. Audio only: Android Auto " +
            "does not allow video. Needs the background playback patch, and Android Auto's " +
            "'Unknown sources' developer setting unless the app was installed by the Play Store.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(androidAutoResourcePatch)

    extendWith("extensions/extension.mpe")

    execute {
        // YouTube creates its media session (used for the notification and background playback)
        // through an obfuscated copy of androidx MediaSessionCompat, which in turn constructs the
        // framework class android.media.session.MediaSession. Framework class names are never
        // obfuscated, so hooking every construction of it works across YouTube versions without
        // version-specific fingerprints.
        //
        // After each `new MediaSession(...)`, hand the new session to the extension, which mirrors
        // it to Android Auto.
        var hookCount = 0

        methodCall(
            definingClass = "Landroid/media/session/MediaSession;",
            name = "<init>",
        ).matchAllMethodIndicesForEach { index ->
            // Do not hook the session the extension itself creates for Android Auto.
            if (definingClass.startsWith(EXTENSION_PACKAGE)) return@matchAllMethodIndicesForEach

            val sessionRegister = when (val instruction = getInstruction<Instruction>(index)) {
                is FiveRegisterInstruction -> instruction.registerC
                is RegisterRangeInstruction -> instruction.startRegister
                else -> throw PatchException("Unexpected MediaSession constructor call: $instruction")
            }

            // The range form works for any register number, including v16 and above.
            addInstruction(
                index + 1,
                "invoke-static/range { v$sessionRegister .. v$sessionRegister }, " +
                        "$BRIDGE_CLASS->onMediaSessionCreated(Landroid/media/session/MediaSession;)V"
            )
            hookCount++
        }

        if (hookCount == 0) {
            throw PatchException("Could not find where YouTube creates its media session")
        }

        Logger.getLogger(this::class.java.name).info("Android Auto: hooked $hookCount media session(s)")
    }
}
