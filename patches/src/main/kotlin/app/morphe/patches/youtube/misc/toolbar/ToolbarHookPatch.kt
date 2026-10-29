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
import app.morphe.patches.youtube.shared.ToolBarButtonFingerprint
import java.lang.ref.WeakReference

private lateinit var toolbarMethod: WeakReference<MutableMethod>

/**
 * Shared infrastructure used by existing toolbar patches.
 *
 * The optional secondary Cast V2 feature is deliberately injected by
 * secondaryCastV2Patch instead of this shared hook, so it is independently selectable.
 */
val toolBarHookPatch = bytecodePatch(
    description = "toolBarHookPatch"
) {
    execute {
        ToolBarButtonFingerprint.clearMatch()
        toolbarMethod = WeakReference(ToolBarPatchFingerprint.method)
    }
}

internal fun hookToolBar(descriptor: String) = toolbarMethod.get()!!.addInstructions(
    0,
    "invoke-static { p0, p1, p2 }, " +
            "$descriptor(Ljava/lang/String;Landroid/view/View;Landroid/widget/ImageView;)V"
)
