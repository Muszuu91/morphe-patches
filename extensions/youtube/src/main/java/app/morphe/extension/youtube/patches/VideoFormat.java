/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import java.util.List;

import com.google.protobuf.MessageLite;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class VideoFormat {

    /**
     * Interface to use obfuscated methods.
     */
    public interface FormatInterface {
        // Methods are added during patching.
        String patch_getMimeType();
        int patch_getWidth();
        int patch_getHeight();
        int patch_getItag();
    }

    /**
     * Injection point.
     */
    public static List<FormatInterface> hookAdaptiveFormats(@Nullable String videoId, @NonNull List<FormatInterface> adaptiveFormats) {
        return Utils.isNotEmpty(videoId) && !"zzzzzzzzzzz".equals(videoId) && !adaptiveFormats.isEmpty()
                ? privateHookAdaptiveFormats(videoId, adaptiveFormats)
                : adaptiveFormats;
    }

    /**
     * Temporary Cast V2 diagnostic hook. Never logs stream URLs or cipher/token data.
     */
    public static void diagnoseStreamingData(
            Object firstStreamingData,
            Object lastStreamingData,
            @Nullable String videoId,
            @Nullable List<?> adaptiveFormats
    ) {
        try {
            Logger.printDebug(() -> "Cast V2: videoId=" + videoId
                    + ", adaptiveFormatsModelCount=" + (adaptiveFormats == null ? -1 : adaptiveFormats.size())
                    + ", sameStreamingDataObject=" + (firstStreamingData == lastStreamingData));

            logStreamingData("first", firstStreamingData);
            if (lastStreamingData != firstStreamingData) {
                logStreamingData("last", lastStreamingData);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Cast V2 streaming data diagnostic failed", ex);
        }
    }

    private static void logStreamingData(String label, Object candidate) {
        if (!(candidate instanceof MessageLite)) {
            Logger.printDebug(() -> "Cast V2: " + label + " object is not MessageLite: "
                    + (candidate == null ? "null" : candidate.getClass().getName()));
            return;
        }

        try {
            PlayerResponseOuterClass.StreamingData data =
                    PlayerResponseOuterClass.StreamingData.parseFrom(((MessageLite) candidate).toByteArray());

            Logger.printDebug(() -> "Cast V2: " + label
                    + " parsed formats=" + data.getFormatsCount()
                    + ", adaptiveFormats=" + data.getAdaptiveFormatsCount());

            for (PlayerResponseOuterClass.Format format : data.getAdaptiveFormatsList()) {
                Logger.printDebug(() -> "Cast V2: " + label
                        + " adaptive itag=" + format.getItag()
                        + ", mimeType=" + format.getMimeType());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Cast V2: cannot parse " + label + " StreamingData", ex);
        }
    }

    private static List<FormatInterface> privateHookAdaptiveFormats(@NonNull String videoId, @NonNull List<FormatInterface> adaptiveFormats) {
        // Code added during patching.
        return adaptiveFormats;
    }
}
