/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.videoplayer;

import android.view.View;
import android.widget.ImageView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.ToolBarPatch;

/**
 * Independent Cast V2 entry point in the video overlay.
 * This button is always visible; it does not depend on the experimental setting.
 */
@SuppressWarnings("unused")
public final class SecondaryCastV2OverlayButton {
    private SecondaryCastV2OverlayButton() {}

    public static void initializeButton(View controlsView) {
        try {
            ImageView button = PlayerOverlayButton.addButton(
                    controlsView,
                    "yt_outline_experimental_chromecast_black_24",
                    view -> ToolBarPatch.openSecondaryCastPicker(view.getContext()),
                    null
            );
            if (button != null) {
                button.setContentDescription("Cast V2");
                button.setVisibility(View.VISIBLE);
            } else {
                Logger.printDebug(() -> "Secondary Cast V2 overlay button was not added: source view unavailable");
            }
        } catch (Throwable error) {
            Logger.printException(() -> "Secondary Cast V2 overlay button initialization failed", error);
        }
    }
}
