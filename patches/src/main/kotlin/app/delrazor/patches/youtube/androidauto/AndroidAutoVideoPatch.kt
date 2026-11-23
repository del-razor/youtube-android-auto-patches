package app.delrazor.patches.youtube.androidauto

import app.delrazor.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import java.util.logging.Logger

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

            // The car screen opens YouTube on a private virtual display. Android only lets an
            // activity open on such a display when it is marked allowEmbedded, otherwise:
            // "SecurityException: Permission Denial: starting Intent ... with launchDisplayId".
            // Mark every activity, so YouTube can open its other screens there too.
            manifest.elements("activity").forEach { activity ->
                activity.setAttribute("android:allowEmbedded", "true")
            }

            // Android refuses the first window on a virtual display unless the caller holds a
            // system permission. Shizuku runs that one launch as the phone's shell user.
            // These are the entries the Shizuku client library declares in its own manifest.
            application.parent!!.insertChild(application.index, "uses-permission") {
                setAttribute("android:name", "moe.shizuku.manager.permission.API_V23")
            }
            application.appendChild("meta-data") {
                setAttribute("android:name", "moe.shizuku.client.V3_SUPPORT")
                setAttribute("android:value", "true")
            }
            // Shizuku hands its link to the app through this provider, at "<package>.shizuku".
            // The official package rename patch also renames authorities that start with the
            // old package name, so this stays right whichever patch runs first.
            val packageName = application.parent!!.attribute("package")!!
            application.appendChild("provider") {
                setAttribute("android:name", "rikka.shizuku.ShizukuProvider")
                setAttribute("android:authorities", "$packageName.shizuku")
                setAttribute("android:multiprocess", "false")
                setAttribute("android:enabled", "true")
                setAttribute("android:exported", "true")
                setAttribute("android:permission", "android.permission.INTERACT_ACROSS_USERS_FULL")
            }

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

private const val AUDIO_SYNC_CLASS = "Lapp/delrazor/extension/youtube/androidauto/CarAudioSync;"

/**
 * Lets the car screen shift YouTube's audio against the video. The car shows video later than it
 * plays sound, because the picture is encoded and streamed to the head unit.
 *
 * YouTube's player times video frames by how far the framework AudioTrack has played. Its calls to
 * AudioTrack.getPlaybackHeadPosition() and AudioTrack.getTimestamp() are sent through
 * CarAudioSync, which reports the position a set number of milliseconds ahead while the car
 * screen is open. Video frames then show earlier, which lines them up with the late car picture.
 * Framework names are never obfuscated, so this needs no version-specific fingerprints.
 */
private val carAudioSyncHookPatch = patch {
    extendWith("extensions/extension.mpe")

    execute {
        val hooks = listOf(
            "getPlaybackHeadPosition" to "(Landroid/media/AudioTrack;)I",
            "getTimestamp" to "(Landroid/media/AudioTrack;Landroid/media/AudioTimestamp;)Z",
        )
        var hookCount = 0

        hooks.forEach { (name, staticSignature) ->
            methodCall(
                definingClass = "Landroid/media/AudioTrack;",
                name = name,
            ).matchAllMethodIndicesForEach(requireMatches = false) { index ->
                if (definingClass.startsWith(EXTENSION_PACKAGE)) return@matchAllMethodIndicesForEach

                // The instance call and the static call take the same registers: the AudioTrack,
                // then the arguments.
                val replacement = when (val instruction = getInstruction<Instruction>(index)) {
                    is FiveRegisterInstruction -> {
                        val registers = listOf(
                            instruction.registerC, instruction.registerD, instruction.registerE,
                            instruction.registerF, instruction.registerG,
                        ).take(instruction.registerCount)
                        "invoke-static { ${registers.joinToString { "v$it" }} }, " +
                                "$AUDIO_SYNC_CLASS->$name$staticSignature"
                    }
                    is RegisterRangeInstruction -> {
                        val last = instruction.startRegister + instruction.registerCount - 1
                        "invoke-static/range { v${instruction.startRegister} .. v$last }, " +
                                "$AUDIO_SYNC_CLASS->$name$staticSignature"
                    }
                    else -> throw PatchException("Unexpected AudioTrack call: $instruction")
                }
                replaceInstruction(index, replacement)
                hookCount++
            }
        }

        // Not fatal: without the hooks the car screen still works, only the sync buttons do nothing.
        Logger.getLogger(this::class.java.name).info(
            "Android Auto: routed $hookCount AudioTrack position call(s) through the audio sync"
        )
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
        carAudioSyncHookPatch,
    )
}
