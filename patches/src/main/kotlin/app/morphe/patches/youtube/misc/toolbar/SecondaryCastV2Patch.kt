/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.toolbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.LayoutConstructorFingerprint
import app.morphe.patches.youtube.shared.startVideoInformerPatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val SECONDARY_CAST_EXTENSION =
    "Lapp/morphe/extension/youtube/patches/ToolBarPatch;"

/**
 * Replaces the action of the Cast button displayed over the video when enabled.
 * A normal tap opens our Cast V2 picker; when disabled, YouTube keeps its native action.
 */
@Suppress("unused")
val secondaryCastV2Patch = bytecodePatch(
    name = "Secondary Cast V2",
    description = "Uses the Cast V2 picker for the Cast button over the video when enabled.",
    default = false,
) {
    dependsOn(sharedExtensionPatch, settingsPatch, startVideoInformerPatch)

    execute {
        // Fail clearly if this YouTube build does not contain the player Cast control.
        resourceId(ResourceType.ID, "media_route_button")

        LayoutConstructorFingerprint.let {
            it.clearMatch()
            it.method.apply {
                val insertIndex = it.instructionMatches.last().index
                val viewRegister = getInstruction<FiveRegisterInstruction>(insertIndex).registerC

                addInstructions(
                    insertIndex,
                    """
                        invoke-static { v$viewRegister }, $SECONDARY_CAST_EXTENSION->hookPlayerCastButton(Landroid/view/View;)V
                    """
                )
            }
        }
    }
}
