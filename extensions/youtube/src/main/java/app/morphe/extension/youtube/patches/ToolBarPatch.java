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
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;

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
        // Avoid changing other toolbar buttons. The enum name varies between YouTube builds.
        if (!enumString.toUpperCase(java.util.Locale.ROOT).contains("CAST")) return;

        imageView.setLongClickable(true);
        imageView.setOnLongClickListener(view -> {
            showSecondaryCastPicker(view.getContext());
            return true;
        });
        imageView.setContentDescription(
                String.valueOf(imageView.getContentDescription()) + ". " +
                "Przytrzymaj, aby otworzyć dodatkowe Cast V2");
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

        DevicePicker(Context context, NsdManager nsd) {
            this.context = context;
            this.nsd = nsd;
        }

        void start() {
            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setTitle("Dodatkowy Cast V2")
                    .setMessage("Wyszukiwanie urządzeń Chromecast / Google Cast…")
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
            CharSequence[] labels = new CharSequence[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                NsdServiceInfo device = devices.get(i);
                String name = device.getServiceName();
                labels[i] = (name == null || name.isEmpty() ? "Google Cast" : name)
                        + "  (" + device.getHost().getHostAddress() + ")";
            }
            dialog.setMessage("Wybierz urządzenie. Ta sesja jest niezależna od natywnego Cast.");
            dialog.setItems(labels, (d, which) -> {
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
            });
        }

        @Override public void onServiceLost(NsdServiceInfo service) {
            devices.removeIf(d -> d.getServiceName().equals(service.getServiceName()));
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
