package app.delrazor.patches.youtube.androidauto

import app.delrazor.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.logging.Logger

private const val VIDEO_BRIDGE_CLASS =
    "Lapp/delrazor/extension/youtube/androidauto/CarVideoBridge;"

private const val CAR_VIDEO_SERVICE_CLASS =
    "app.delrazor.extension.youtube.androidauto.CarVideoService"

private const val MEDIA_CODEC_CLASS = "Landroid/media/MediaCodec;"

/**
 * Adds a "projection" entry for Android Auto: a service that the unofficial Android Auto SDK
 * (bundled in the extension) uses to draw a full screen on the car display.
 */
private val androidAutoVideoResourcePatch = patch {
    dependsOn(androidAutoResourcePatch)

    execute {
        // Rewrite the car app description written by the audio patch, adding projection.
        // Same set of entries Fermata Auto declares.
        resourceFile("res/xml/$CAR_APP_DESCRIPTION_NAME.xml", false).apply {
            parentFile.mkdirs()
            writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <automotiveApp>
                    <uses name="media" />
                    <uses name="service" />
                    <uses name="projection" />
                </automotiveApp>
                """.trimIndent()
            )
        }

        resourceXml("AndroidManifest.xml").use { manifest ->
            val application = manifest.elements("application").first()

            application.appendChild("service") {
                setAttribute("android:name", CAR_VIDEO_SERVICE_CLASS)
                // The name shown in the Android Auto launcher, next to the audio entry.
                setAttribute("android:label", "YouTube Video")
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
    description = "Adds a YouTube Video screen to Android Auto that shows YouTube's own player " +
            "on the car display, following the car's own 'video allowed' signal. Android Auto " +
            "only lists it when the patched app is installed with the Play Store as installer, " +
            "for example with KingInstaller.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        androidAutoPatch,
        androidAutoVideoResourcePatch,
    )

    extendWith("extensions/extension.mpe")

    execute {
        // YouTube decodes video with android.media.MediaCodec. Route every configure() and
        // setOutputSurface() call through CarVideoBridge, which can send the picture to the
        // car screen instead of the phone. Framework names are never obfuscated, so this
        // does not depend on the YouTube version.
        val configureCount = redirectMediaCodecCall("configure")
        if (configureCount == 0) {
            throw PatchException("Could not find where YouTube sets up its video decoder")
        }
        val setSurfaceCount = redirectMediaCodecCall("setOutputSurface")

        Logger.getLogger(this::class.java.name).info(
            "Android Auto video: redirected $configureCount configure and " +
                    "$setSurfaceCount setOutputSurface call(s)"
        )
    }
}

/**
 * Replaces `invoke-virtual { codec, args... }, MediaCodec->name(args)` with
 * `invoke-static { codec, args... }, CarVideoBridge->name(MediaCodec, args)`.
 * The registers stay the same, because the codec becomes the first argument.
 *
 * @return How many calls were replaced.
 */
private fun redirectMediaCodecCall(name: String): Int {
    var count = 0

    methodCall(
        definingClass = MEDIA_CODEC_CLASS,
        name = name,
    ).matchAllMethodIndicesForEach(requireMatches = false) { index ->
        // Never redirect the bridge's own calls to the real MediaCodec methods.
        if (definingClass.startsWith(EXTENSION_PACKAGE)) return@matchAllMethodIndicesForEach

        val instruction = getInstruction<Instruction>(index)
        val reference = (instruction as ReferenceInstruction).reference as MethodReference
        val descriptor = "($MEDIA_CODEC_CLASS" +
                reference.parameterTypes.joinToString("") + ")" + reference.returnType
        val target = "$VIDEO_BRIDGE_CLASS->$name$descriptor"

        val smali = when (instruction) {
            is FiveRegisterInstruction -> {
                val registers = listOf(
                    instruction.registerC,
                    instruction.registerD,
                    instruction.registerE,
                    instruction.registerF,
                    instruction.registerG,
                ).take(instruction.registerCount)
                "invoke-static { ${registers.joinToString { "v$it" }} }, $target"
            }

            is RegisterRangeInstruction -> {
                val last = instruction.startRegister + instruction.registerCount - 1
                "invoke-static/range { v${instruction.startRegister} .. v$last }, $target"
            }

            else -> throw PatchException("Unexpected MediaCodec call: $instruction")
        }

        replaceInstruction(index, smali)
        count++
    }

    return count
}
