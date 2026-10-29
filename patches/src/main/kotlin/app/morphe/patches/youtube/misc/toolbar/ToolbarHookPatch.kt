/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.toolbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.ToolBarButtonFingerprint
import java.lang.ref.WeakReference

private lateinit var toolbarMethod: WeakReference<MutableMethod>

/**
 * Shared infrastructure for other toolbar patches.
 * Secondary Cast V2 has its own independently selectable patch.
 *
 * Keep the hook target on YouTube's toolbar method. The extension helper is
 * injected into the extension DEX and is not a valid replacement fingerprint
 * target for this patching stage.
 */
val toolBarHookPatch = bytecodePatch(
    description = "toolBarHookPatch"
) {
    dependsOn(sharedExtensionPatch)

    execute {
        ToolBarButtonFingerprint.let {
            it.clearMatch()
            toolbarMethod = WeakReference(it.method)
        }
    }
}

internal fun hookToolBar(descriptor: String) = toolbarMethod.get()!!.addInstructions(
    0,
    "invoke-static { p0, p1, p2 }, " +
            "$descriptor(Ljava/lang/String;Landroid/view/View;Landroid/widget/ImageView;)V"
)
