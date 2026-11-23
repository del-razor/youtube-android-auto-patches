package app.delrazor.patches.youtube.androidauto

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

internal const val MEDIA_BROWSER_ACTION = "android.media.browse.MediaBrowserService"

/**
 * YouTube's own Android Auto entry points. YouTube ships a Car App Library app (listed in the car
 * as "YouTube", Premium audio only) and a media browser service. Both show as extra launcher
 * entries next to ours, so their intent actions are removed. The services stay in the app.
 */
internal val YOUTUBE_CAR_ENTRY_ACTIONS = setOf(
    MEDIA_BROWSER_ACTION,
    "androidx.car.app.CarAppService",
)

private const val BRIDGE_CLASS =
    "Lapp/delrazor/extension/youtube/androidauto/AndroidAutoBridge;"

private const val CAR_APP_DESCRIPTION_NAME = "delrazor_android_auto_app_desc"

private const val CAR_APP_DESCRIPTION_PATH = "res/xml/$CAR_APP_DESCRIPTION_NAME.xml"

/**
 * What both Android Auto patches need in the manifest:
 *
 * - `com.google.android.gms.car.application` meta-data pointing to an XML file that lists the
 *   kinds of car entries the app has. Each patch adds its kind with [addCarAppUse].
 * - YouTube's own media browser service taken out of Android Auto's lookup. Testing on YouTube
 *   21.30.216 showed an extra Android Auto entry that only says "No items", most likely that
 *   service. Without this, Android Auto lists it as soon as the app declares car support.
 */
internal val carAppSupportPatch = patch {
    execute {
        // UNTESTED: assumes the patcher compiles a new res/xml file written this way,
        // the same way the official patches' copyResources() helper adds files.
        resourceFile(CAR_APP_DESCRIPTION_PATH, false).apply {
            parentFile.mkdirs()
            writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <automotiveApp>
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
                            "The patch must be updated for it."
                )
            }

            manifest.elements("action")
                .filter { it.attribute("android:name") in YOUTUBE_CAR_ENTRY_ACTIONS }
                .toList()
                .forEach { action ->
                    val intentFilter = action.parent!!
                    Logger.getLogger(this::class.java.name).info(
                        "Android Auto: hiding YouTube's own car entry " +
                                "${action.attribute("android:name")} on " +
                                intentFilter.parent?.attribute("android:name")
                    )
                    action.remove()
                    if (intentFilter.children.isEmpty()) intentFilter.remove()
                }

            manifest.elements("application").first().appendChild("meta-data") {
                setAttribute("android:name", "com.google.android.gms.car.application")
                setAttribute("android:resource", "@xml/$CAR_APP_DESCRIPTION_NAME")
            }
        }
    }
}

/**
 * Adds `<uses name="[name]"/>` to the car app description written by [carAppSupportPatch].
 * Call from the execute block of a patch that depends on [carAppSupportPatch].
 */
internal fun addCarAppUse(name: String) {
    val file = resourceFile(CAR_APP_DESCRIPTION_PATH, false)
    val text = file.readText()
    val entry = "<uses name=\"$name\" />"
    if (entry in text) return
    file.writeText(text.replace("</automotiveApp>", "    $entry\n</automotiveApp>"))
}

/**
 * Hands every media session YouTube creates to the extension (AndroidAutoBridge), which the
 * car entries use for the title, artwork and play, pause and skip controls.
 */
internal val mediaSessionHookPatch = patch {
    extendWith("extensions/extension.mpe")

    execute {
        // YouTube creates its media session (used for the notification and background playback)
        // through an obfuscated copy of androidx MediaSessionCompat, which in turn constructs the
        // framework class android.media.session.MediaSession. Framework class names are never
        // obfuscated, so hooking every construction of it works across YouTube versions without
        // version-specific fingerprints.
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
