/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.patches;

import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import app.morphe.extension.shared.Logger;

/**
 * Minimal Cast V2 transport. It opens a separate TLS connection to the receiver,
 * launches YouTube's receiver app, and requests its MDX session status.
 *
 * YouTube's Lounge pairing/bind flow is separate and must succeed before a video
 * can be queued. This class deliberately reports that boundary honestly.
 */
final class CastV2Client {
    private static final String SOURCE = "sender-0";
    private static final String RECEIVER = "receiver-0";
    private static final String CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String YOUTUBE_MDX = "urn:x-cast:com.google.youtube.mdx";
    private static final String YOUTUBE_APP_ID = "233637DE";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    interface Callback {
        void onResult(String message, boolean ready);
    }

    private CastV2Client() { }

    static void connect(NsdServiceInfo device, Callback callback) {
        WORKER.execute(() -> {
            String result;
            boolean ready = false;
            try {
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, new TrustManager[]{new X509TrustManager() {
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                }}, new SecureRandom());

                try (SSLSocket socket = (SSLSocket) ssl.getSocketFactory().createSocket()) {
                    socket.connect(new InetSocketAddress(device.getHost(), device.getPort()), 7000);
                    socket.setSoTimeout(6500);
                    socket.startHandshake();
                    DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                    send(out, RECEIVER, CONNECTION, "{\"type\":\"CONNECT\",\"origin\":{},\"userAgent\":\"Morphe secondary Cast V2\"}");
                    send(out, RECEIVER, CONNECTION, "{\"type\":\"LAUNCH\",\"appId\":\"" + YOUTUBE_APP_ID + "\",\"requestId\":1}");
                    boolean statusReceived = false;
                    boolean statusRequestSent = false;
                    long deadline = System.currentTimeMillis() + 9000;
                    while (System.currentTimeMillis() < deadline) {
                        if (!statusRequestSent) {
                            send(out, RECEIVER, YOUTUBE_MDX,
                                    "{\\"type\\":\\"getMdxSessionStatus\\",\\"requestId\\":2}");
                            statusRequestSent = true;
                        }
                        JSONObject message = readMessage(in);
                        if (message == null) continue;
                        String ns = message.optString("namespace");
                        String payload = message.optString("payload");
                        if (CONNECTION.equals(ns)) {
                            JSONObject p = new JSONObject(payload);
                            if ("CLOSE".equals(p.optString("type"))) break;
                            if ("LAUNCH_ERROR".equals(p.optString("type"))) {
                                throw new IllegalStateException("Odbiornik YouTube odrzucił uruchomienie");
                            }
                        }
                        if (HEARTBEAT.equals(ns)) {
                            JSONObject p = new JSONObject(payload);
                            if ("PING".equals(p.optString("type"))) {
                                send(out, RECEIVER, HEARTBEAT, "{\\"type\\":\\"PONG\\"}");
                            }
                        }
                        if (YOUTUBE_MDX.equals(ns)) {
                            JSONObject p = new JSONObject(payload);
                            if ("mdxSessionStatus".equals(p.optString("type"))) {
                                statusReceived = true;
                                break;
                            }
                        }
                    }
                    if (statusReceived) {
                        result = "Odbiornik YouTube odpowiedział na MDX. Transport Cast V2 działa; przekazanie filmu wymaga jeszcze autoryzacji Lounge.";
                        ready = false;
                    } else {
                        result = "Urządzenie przyjęło połączenie, ale nie zwróciło sesji YouTube MDX.";
                    }
                }
            } catch (Throwable error) {
                Logger.printException(() -> "Secondary Cast V2 connection failed", error);
                result = "Połączenie Cast V2 nie powiodło się: " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : " — " + error.getMessage());
            }
            final String finalResult = result;
            final boolean finalReady = ready;
            MAIN.post(() -> callback.onResult(finalResult, finalReady));
        });
    }

    private static void send(DataOutputStream out, String destination, String namespace, String json) throws Exception {
        ByteArrayOutputStream protobuf = new ByteArrayOutputStream();
        writeVarintField(protobuf, 1, 0); // protocol_version
        writeStringField(protobuf, 2, SOURCE);
        writeStringField(protobuf, 3, destination);
        writeStringField(protobuf, 4, namespace);
        writeVarintField(protobuf, 5, 0); // STRING payload
        writeStringField(protobuf, 6, json);
        byte[] bytes = protobuf.toByteArray();
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    private static JSONObject readMessage(DataInputStream in) throws Exception {
        int length;
        try { length = in.readInt(); } catch (java.net.SocketTimeoutException timeout) { return null; }
        if (length <= 0 || length > 1024 * 1024) throw new IllegalStateException("Nieprawidłowy rozmiar ramki Cast: " + length);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        String namespace = null;
        String payload = null;
        int pos = 0;
        while (pos < bytes.length) {
            long tagRead = readVarint(bytes, pos);
            int tag = (int) tagRead;
            pos = (int) (tagRead >>> 32);
            int field = tag >>> 3;
            int wire = tag & 7;
            if (wire == 0) {
                long val = readVarint(bytes, pos);
                pos = (int) (val >>> 32);
            } else if (wire == 2) {
                long lenRead = readVarint(bytes, pos);
                int len = (int) lenRead;
                pos = (int) (lenRead >>> 32);
                if (len < 0 || pos + len > bytes.length) throw new IllegalStateException("Uszkodzona ramka Cast");
                String value = new String(bytes, pos, len, StandardCharsets.UTF_8);
                if (field == 4) namespace = value;
                if (field == 6) payload = value;
                pos += len;
            } else {
                throw new IllegalStateException("Nieobsługiwany typ pola Cast: " + wire);
            }
        }
        if (payload == null) return null;
        JSONObject message = new JSONObject();
        message.put("namespace", namespace == null ? "" : namespace);
        message.put("payload", payload);
        return message;
    }

    // Returns the varint value in low 32 bits and the next byte offset in high 32 bits.
    private static long readVarint(byte[] bytes, int offset) {
        long value = 0;
        int shift = 0;
        int pos = offset;
        while (pos < bytes.length && shift < 64) {
            int b = bytes[pos++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return ((long) pos << 32) | (value & 0xffffffffL);
            shift += 7;
        }
        throw new IllegalStateException("Nieprawidłowy varint Cast");
    }

    private static void writeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, ((long) field << 3));
        writeVarint(out, value);
    }

    private static void writeStringField(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, ((long) field << 3) | 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }
}
