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
import java.lang.ref.WeakReference

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/ToolBarPatch;"

private lateinit var toolbarMethod: WeakReference<MutableMethod>

/**
 * Shared infrastructure for other toolbar patches.
 *
 * The hook is inserted into ToolBarPatch.hookToolBar(String, View, ImageView).
 * Do not use ToolBarButtonFingerprint here: that fingerprint resolves to a
 * YouTube method with different parameters (MenuItem, Context), which makes
 * the injected p0/p1/p2 invocation fail DEX verification.
 */
val toolBarHookPatch = bytecodePatch(
    description = "toolBarHookPatch"
) {
    dependsOn(sharedExtensionPatch)

    execute {
        ToolBarPatchFingerprint.clearMatch()
        toolbarMethod = WeakReference(ToolBarPatchFingerprint.method)
    }
}

internal fun hookToolBar(descriptor: String) = toolbarMethod.get()!!.addInstructions(
    0,
    "invoke-static { p0, p1, p2 }, " +
            "$descriptor(Ljava/lang/String;Landroid/view/View;Landroid/widget/ImageView;)V"
)
