package app.template.patches.pocketcasts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.mutableClassDefBy
import app.template.patches.shared.clearBody
import com.android.tools.smali.dexlib2.Opcode

/**
 * Pocket Casts 8.25-rc-1, versionCode 9468.
 *
 * Deliberately changes ONLY the UI predicate which decides whether to show
 * the generated-transcript paywall. Does not forge a subscription, modify
 * account tokens, replace authentication handlers, or change server replies.
 *
 * This can display a generated transcript that the app has already discovered
 * and downloaded. It cannot produce transcripts for episodes without a source,
 * nor can it work around server-side denials of transcript requests.
 *
 * Verification against the submitted APK:
 *   - UiState.toString() contains "UiState(transcriptState=" and ", isPlusUser=".
 *   - The class is Lfse; in this exact APK (name is obfuscated, not hardcoded).
 *   - Its paywall getter is d()Z and is the unique boolean zero-argument
 *     instance method beginning with IGET_BOOLEAN and invoking an interface.
 */
private val transcriptUiStateToStringFingerprint = Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    strings = listOf("UiState(transcriptState=", ", isPlusUser="),
)

@Suppress("unused")
val showGeneratedTranscriptsPatch = bytecodePatch(
    name = "Show generated transcripts",
    description = "Hides the local transcript paywall for already-available transcripts; " +
        "does not change account login, subscription status, or server access.",
    default = false,
) {
    compatibleWith(
        Compatibility(
            name = "Pocket Casts",
            packageName = "au.com.shiftyjelly.pocketcasts",
            apkFileType = ApkFileType.APK,
            appIconColor = 0xF43F5E,
            targets = listOf(AppTarget(version = "8.25-rc-1", versionCode = 9468)),
        ),
    )

    execute {
        val uiClass = mutableClassDefBy(transcriptUiStateToStringFingerprint.originalClassDef.type)

        // Fail closed on unexpected app code instead of touching login or a
        // similarly shaped method in a different Pocket Casts build.
        val candidates = uiClass.methods.filter { method ->
            method.returnType == "Z" &&
                method.parameters.isEmpty() &&
                method.implementation?.instructions?.let { instructions ->
                    instructions.firstOrNull()?.opcode == Opcode.IGET_BOOLEAN &&
                        instructions.any { it.opcode == Opcode.INVOKE_INTERFACE }
                } == true
        }
        val paywallGetter = candidates.singleOrNull()
            ?: throw PatchException(
                "Expected one UiState generated-transcript paywall getter; found ${candidates.size}. " +
                    "This patch is only verified for Pocket Casts 8.25-rc-1 (9468).",
            )

        paywallGetter.clearBody()
        paywallGetter.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """.trimIndent(),
        )
    }
}
