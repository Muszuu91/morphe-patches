/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.patches;

import android.app.AlertDialog;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Entry point for the optional secondary Cast V2 picker.
 *
 * A long press is intentionally used so YouTube's native Cast button and native
 * MediaRouter session remain untouched on a normal tap.
 */
@SuppressWarnings("unused")
public class ToolBarPatch {
    private static final ThreadLocal<String> pendingIconEnum = new ThreadLocal<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void setToolbarIconEnum(@Nullable Enum<?> iconEnum) {
        pendingIconEnum.set(iconEnum == null ? null : iconEnum.name());
    }

    public static void setToolbarImageView(ImageView imageView) {
        String enumName = pendingIconEnum.get();
        pendingIconEnum.remove();
        if (enumName != null && imageView.getParent() instanceof View parentView) {
            hookToolBar(enumName, parentView, imageView);
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
        if (!isCast) return;

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
