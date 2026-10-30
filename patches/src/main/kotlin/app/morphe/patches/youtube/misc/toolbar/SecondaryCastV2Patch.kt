/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.toolbar

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.ToolBarButtonFingerprint
import app.morphe.patches.youtube.shared.startVideoInformerPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val SECONDARY_CAST_EXTENSION =
    "Lapp/morphe/extension/youtube/patches/ToolBarPatch;"

/**
 * Adds independent Cast V2 entry points to the YouTube toolbar and player overlay.
 * The native YouTube Cast control is left untouched; the added buttons are always visible.
 */
@Suppress("unused")
val secondaryCastV2Patch = bytecodePatch(
    name = "Secondary Cast V2",
    description = "Adds always-visible independent Cast V2 buttons to the toolbar and player overlay. The original YouTube Cast button remains unchanged.",
    // This patch has no explicit compatibility metadata, so it must not default to enabled.
    default = false,
) {
    // settingsPatch remains temporarily for compatibility with the existing patch bundle setting.
    dependsOn(sharedExtensionPatch, settingsPatch, startVideoInformerPatch, playerOverlayButtonsHookPatch)

    execute {
        // Always-visible independent button on the video overlay.
        addPlayerBottomButton("Lapp/morphe/extension/youtube/videoplayer/SecondaryCastV2OverlayButton;")

        ToolBarButtonFingerprint.let {
            it.clearMatch()
            it.method.apply {
                val enumIndex = it.instructionMatches[3].index
                val enumRegister = getInstruction<OneRegisterInstruction>(enumIndex).registerA

                val imageViewIndex = it.instructionMatches[6].index
                val imageViewRegister = getInstruction<OneRegisterInstruction>(imageViewIndex).registerA

                addInstructionsAtControlFlowLabel(
                    imageViewIndex + 1,
                    """
                        invoke-static { v$imageViewRegister }, $SECONDARY_CAST_EXTENSION->setToolbarImageView(Landroid/widget/ImageView;)V
                    """
                )
                addInstructionsAtControlFlowLabel(
                    enumIndex + 1,
                    "invoke-static { v$enumRegister }, $SECONDARY_CAST_EXTENSION->setToolbarIconEnum(Ljava/lang/Enum;)V"
                )
            }
        }
    }
}
