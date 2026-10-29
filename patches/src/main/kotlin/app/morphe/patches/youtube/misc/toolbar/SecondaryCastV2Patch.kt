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
import app.morphe.patches.youtube.shared.ToolBarButtonFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val SECONDARY_CAST_EXTENSION =
    "Lapp/morphe/extension/youtube/patches/ToolBarPatch;"

/**
 * Adds the optional long-press Cast V2 picker without enabling it as a side effect
 * of unrelated patches that use the shared toolbar hook.
 */
val secondaryCastV2Patch = bytecodePatch(
    description = "Secondary Cast V2"
) {
    dependsOn(
        sharedExtensionPatch,
        toolBarHookPatch,
    )

    execute {
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
