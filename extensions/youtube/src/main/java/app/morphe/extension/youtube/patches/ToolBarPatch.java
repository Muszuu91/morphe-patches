/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.patches;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Entry point for the optional secondary Cast V2 picker.
 *
 * A runtime setting chooses between YouTube's native Cast action and the independent V2 picker.
 */
@SuppressWarnings("unused")
public class ToolBarPatch {
    private static final ThreadLocal<String> pendingIconEnum = new ThreadLocal<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<ImageView, Drawable> originalCastIcons = new WeakHashMap<>();

    public static void setToolbarIconEnum(@Nullable Enum<?> iconEnum) {
        String enumName = iconEnum == null ? null : iconEnum.name();
        pendingIconEnum.set(enumName);
        Logger.printDebug(() -> "Secondary Cast V2: toolbar enum=" + enumName);
    }

    public static void setToolbarImageView(ImageView imageView) {
        String enumName = pendingIconEnum.get();
        pendingIconEnum.remove();

        if (imageView == null) {
            Logger.printDebug(() -> "Secondary Cast V2: toolbar ImageView is null");
            return;
        }

        Object parent = imageView.getParent();
        Logger.printDebug(() -> "Secondary Cast V2: ImageView callback; enum=" + enumName
                + ", parent=" + (parent == null ? "null" : parent.getClass().getName())
                + ", drawable=" + (imageView.getDrawable() == null
                ? "null" : imageView.getDrawable().getClass().getName())
                + ", contentDescription=" + imageView.getContentDescription());

        if (enumName != null && parent instanceof View parentView) {
            if (!originalCastIcons.containsKey(imageView)) {
                originalCastIcons.put(imageView, imageView.getDrawable());
            }
            hookToolBar(enumName, parentView, imageView);
        } else {
            Logger.printDebug(() -> "Secondary Cast V2: hook skipped; enum missing or parent is not a View");
        }
    }

    private static void hookToolBar(String enumString, View parentView, ImageView imageView) {
        String enumName = enumString == null ? "" : enumString.toUpperCase(java.util.Locale.ROOT);
        String iconDescription = String.valueOf(imageView.getContentDescription())
                .toUpperCase(java.util.Locale.ROOT);
        String parentDescription = String.valueOf(parentView.getContentDescription())
                .toUpperCase(java.util.Locale.ROOT);
        boolean isCast = enumName.contains("CAST")
                || iconDescription.contains("CAST")
                || iconDescription.contains("CHROMECAST")
                || parentDescription.contains("CAST")
                || parentDescription.contains("CHROMECAST");

        Logger.printDebug(() -> "Secondary Cast V2: detection enum=" + enumName
                + ", iconDescription=" + iconDescription
                + ", parentDescription=" + parentDescription
                + ", isCast=" + isCast);

        if (!isCast) return;

        Drawable original = originalCastIcons.get(imageView);
        Logger.printDebug(() -> "Secondary Cast V2: Cast matched; originalDrawable="
                + (original == null ? "null" : original.getClass().getName())
                + ", enabled=" + Settings.SECONDARY_CAST_V2_ENABLED.get());

        // OFF keeps the exact YouTube drawable; ON adds a small, visible V2 badge to it.
        updateCastIconBadge(imageView, Settings.SECONDARY_CAST_V2_ENABLED.get());

        /*
         * Read the setting at the beginning of every touch gesture:
         * OFF  -> return false and let YouTube handle its original Cast click.
         * ON   -> consume the tap and open the independent Cast V2 picker.
         *
         * This keeps the setting runtime-switchable without rebuilding the APK.
         */
        parentView.setOnTouchListener(new View.OnTouchListener() {
            private boolean useSecondaryCast;
            private boolean moved;
            private float downX;
            private float downY;
            private final int touchSlop =
                    ViewConfiguration.get(parentView.getContext()).getScaledTouchSlop();

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        useSecondaryCast = Settings.SECONDARY_CAST_V2_ENABLED.get();
                        updateCastIconBadge(imageView, useSecondaryCast);
                        moved = false;
                        downX = event.getX();
                        downY = event.getY();
                        return useSecondaryCast;

                    case MotionEvent.ACTION_MOVE:
                        if (!useSecondaryCast) return false;
                        if (Math.abs(event.getX() - downX) > touchSlop
                                || Math.abs(event.getY() - downY) > touchSlop) {
                            moved = true;
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!useSecondaryCast) return false;
                        boolean launchPicker = !moved;
                        useSecondaryCast = false;
                        if (launchPicker) showSecondaryCastPicker(view.getContext());
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        boolean consumed = useSecondaryCast;
                        useSecondaryCast = false;
                        return consumed;

                    default:
                        return useSecondaryCast;
                }
            }
        });
    }


    private static void updateCastIconBadge(ImageView imageView, boolean enabled) {
        Drawable original = originalCastIcons.get(imageView);
        if (original == null) return;

        Drawable current = imageView.getDrawable();
        if (enabled) {
            if (!(current instanceof CastV2BadgeDrawable)) {
                imageView.setImageDrawable(new CastV2BadgeDrawable(original));
            }
        } else if (current instanceof CastV2BadgeDrawable) {
            imageView.setImageDrawable(original);
        }
    }

    /**
     * Draws a compact V2 pill over the original Cast artwork without replacing or tinting it.
     */
    private static final class CastV2BadgeDrawable extends Drawable implements Drawable.Callback {
        private final Drawable original;
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect textBounds = new Rect();
        private final RectF badgeBounds = new RectF();

        CastV2BadgeDrawable(Drawable original) {
            this.original = original.mutate();
            this.original.setCallback(this);
            backgroundPaint.setColor(Color.rgb(210, 35, 45));
            textPaint.setColor(Color.WHITE);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            original.setBounds(bounds);
            original.draw(canvas);

            float density = getDensity();
            float textSize = Math.max(6f * density, bounds.width() * 0.29f);
            textPaint.setTextSize(textSize);
            textPaint.getTextBounds("V2", 0, 2, textBounds);

            float horizontalPadding = 2f * density;
            float verticalPadding = 0.8f * density;
            float badgeWidth = textPaint.measureText("V2") + horizontalPadding * 2f;
            float badgeHeight = textBounds.height() + verticalPadding * 2f;
            float right = bounds.right + 1f * density;
            float bottom = bounds.bottom + 1f * density;
            badgeBounds.set(right - badgeWidth, bottom - badgeHeight,
                    right, bottom);
            canvas.drawRoundRect(badgeBounds, 2f * density, 2f * density, backgroundPaint);
            canvas.drawText("V2", badgeBounds.centerX(),
                    badgeBounds.centerY() - textBounds.exactCenterY(), textPaint);
        }

        private float getDensity() {
            return getBounds().width() > 0
                    ? getBounds().width() / 24f
                    : 1f;
        }

        @Override public void setAlpha(int alpha) {
            original.setAlpha(alpha);
            backgroundPaint.setAlpha(alpha);
            textPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            original.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override public int getIntrinsicWidth() {
            return original.getIntrinsicWidth();
        }

        @Override public int getIntrinsicHeight() {
            return original.getIntrinsicHeight();
        }

        @Override protected void onBoundsChange(Rect bounds) {
            original.setBounds(bounds);
        }

        @Override public boolean isStateful() {
            return original.isStateful();
        }

        @Override protected boolean onStateChange(int[] state) {
            return original.setState(state);
        }

        @Override protected boolean onLevelChange(int level) {
            return original.setLevel(level);
        }

        @Override public boolean setVisible(boolean visible, boolean restart) {
            original.setVisible(visible, restart);
            return super.setVisible(visible, restart);
        }

        @Override public void jumpToCurrentState() {
            original.jumpToCurrentState();
        }

        @Override public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override public void unscheduleDrawable(Drawable who, Runnable what) {
            unscheduleSelf(what);
        }
    }

    /** Opens the independent picker from other Morphe UI entry points. */
    public static void openSecondaryCastPicker(Context context) {
        showSecondaryCastPicker(context);
    }

    private static void showSecondaryCastPicker(Context context) {
        try {
            NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) {
                toast(context, "Usługa wykrywania urządzeń Cast jest niedostępna");
                return;
            }
            new DevicePicker(context, nsd).start();
        } catch (Throwable error) {
            Logger.printException(() -> "Secondary Cast picker failed", error);
            toast(context, "Nie udało się uruchomić dodatkowego Cast V2");
        }
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> Toast.makeText(context.getApplicationContext(), message,
                Toast.LENGTH_LONG).show());
    }

    private static final class DevicePicker implements NsdManager.DiscoveryListener {
        private final Context context;
        private final NsdManager nsd;
        private final List<NsdServiceInfo> devices = new ArrayList<>();
        private boolean stopped;
        private AlertDialog dialog;
        private ArrayAdapter<String> adapter;

        DevicePicker(Context context, NsdManager nsd) {
            this.context = context;
            this.nsd = nsd;
        }

        void start() {
            adapter = new ArrayAdapter<>(context, android.R.layout.select_dialog_item);
            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setTitle("Dodatkowy Cast V2")
                    .setMessage("Wyszukiwanie urządzeń Chromecast / Google Cast…")
                    .setAdapter(adapter, (d, which) -> {
                        if (which < 0 || which >= devices.size()) return;
                        NsdServiceInfo selected = devices.get(which);
                        stop();
                        toast(context, "Łączenie z " + selected.getServiceName() + "…");
                        CastV2Client.connect(selected, (message, ready) -> {
                            new AlertDialog.Builder(context)
                                    .setTitle("Dodatkowy Cast V2")
                                    .setMessage(message)
                                    .setPositiveButton("OK", null)
                                    .show();
                        });
                    })
                    .setNegativeButton("Zamknij", (d, w) -> stop());
            dialog = builder.create();
            dialog.setOnDismissListener(d -> stop());
            dialog.show();
            try {
                nsd.discoverServices("_googlecast._tcp.", NsdManager.PROTOCOL_DNS_SD, this);
            } catch (Throwable error) {
                stop();
                toast(context, "Nie można rozpocząć wyszukiwania Cast");
            }
        }

        @Override public void onDiscoveryStarted(String type) { }

        @Override public void onServiceFound(NsdServiceInfo service) {
            if (stopped) return;
            if (!"_googlecast._tcp.".equals(service.getServiceType())) return;
            nsd.resolveService(service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int code) {
                    Logger.printDebug(() -> "Cast resolve failed: " + code);
                }
                @Override public void onServiceResolved(NsdServiceInfo info) {
                    if (stopped || info.getHost() == null || info.getPort() <= 0) return;
                    for (NsdServiceInfo existing : devices) {
                        if (existing.getHost().equals(info.getHost())) return;
                    }
                    devices.add(info);
                    MAIN.post(DevicePicker.this::refreshDialog);
                }
            });
        }

        private void refreshDialog() {
            if (stopped || dialog == null || !dialog.isShowing()) return;
            if (devices.isEmpty()) return;
            adapter.clear();
            for (NsdServiceInfo device : devices) {
                String name = device.getServiceName();
                adapter.add((name == null || name.isEmpty() ? "Google Cast" : name)
                        + "  (" + device.getHost().getHostAddress() + ")");
            }
            adapter.notifyDataSetChanged();
            dialog.setMessage("Wybierz urządzenie. Ta sesja jest niezależna od natywnego Cast.");
        }

        @Override public void onServiceLost(NsdServiceInfo service) {
            devices.removeIf(d -> java.util.Objects.equals(d.getServiceName(), service.getServiceName()));
            MAIN.post(this::refreshDialog);
        }
        @Override public void onDiscoveryStopped(String type) { }
        @Override public void onStartDiscoveryFailed(String type, int code) { stop(); }
        @Override public void onStopDiscoveryFailed(String type, int code) { stop(); }

        private void stop() {
            if (stopped) return;
            stopped = true;
            try { nsd.stopServiceDiscovery(this); } catch (Throwable ignored) { }
        }
    }
}
