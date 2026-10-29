/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.patches;

import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
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
 * Independent Cast V2 + YouTube Lounge sender. It does not use YouTube's native
 * MediaRouter session. Lounge is an undocumented API and may change.
 */
final class CastV2Client {
    private static final String SOURCE = "sender-0";
    private static final String RECEIVER = "receiver-0";
    private static final String CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String YOUTUBE_MDX = "urn:x-cast:com.google.youtube.mdx";
    private static final String YOUTUBE_APP_ID = "233637DE";
    private static final String LOUNGE = "https://www.youtube.com/api/lounge";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    interface Callback { void onResult(String message, boolean ready); }

    private CastV2Client() { }

    static void connect(NsdServiceInfo device, Callback callback) {
        WORKER.execute(() -> {
            String result;
            boolean ready = false;
            try {
                String screenId = openYouTubeReceiver(device);
                if (screenId == null || screenId.isEmpty()) {
                    throw new IllegalStateException("Odbiornik YouTube nie zwrócił screenId MDX");
                }
                String loungeToken = fetchLoungeToken(screenId);
                LoungeSession session = bindLounge(loungeToken);
                String videoId = VideoInformation.getVideoId();
                if (videoId == null || videoId.trim().isEmpty()) {
                    throw new IllegalStateException("Nie wykryto aktualnego filmu YouTube");
                }
                playVideo(session, loungeToken, videoId);
                result = "Wysłano film " + videoId + " do wybranego urządzenia przez niezależną sesję Cast V2.";
                ready = true;
            } catch (Throwable error) {
                Logger.printException(() -> "Secondary Cast V2 connection failed", error);
                result = "Cast V2: " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : " — " + error.getMessage());
            }
            final String finalResult = result;
            final boolean finalReady = ready;
            MAIN.post(() -> callback.onResult(finalResult, finalReady));
        });
    }

    private static String openYouTubeReceiver(NsdServiceInfo device) throws Exception {
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        }}, new SecureRandom());

        try (SSLSocket socket = (SSLSocket) ssl.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(device.getHost(), device.getPort()), 7000);
            socket.setSoTimeout(2500);
            socket.startHandshake();
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            send(out, RECEIVER, CONNECTION, "{\"type\":\"CONNECT\",\"origin\":{},\"userAgent\":\"Morphe secondary Cast V2\"}");
            send(out, RECEIVER, CONNECTION, "{\"type\":\"LAUNCH\",\"appId\":\"" + YOUTUBE_APP_ID + "\",\"requestId\":1}");
            long deadline = System.currentTimeMillis() + 15000;
            long nextStatusRequest = 0;
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextStatusRequest) {
                    send(out, RECEIVER, YOUTUBE_MDX,
                            "{\\"type\\":\\"getMdxSessionStatus\\",\\"requestId\\":2}");
                    nextStatusRequest = System.currentTimeMillis() + 2000;
                }
                JSONObject message;
                try { message = readMessage(in); }
                catch (java.net.SocketTimeoutException timeout) { continue; }
                if (message == null) continue;
                String namespace = message.optString("namespace");
                String payload = message.optString("payload");
                if (CONNECTION.equals(namespace)) {
                    JSONObject p = new JSONObject(payload);
                    if ("LAUNCH_ERROR".equals(p.optString("type"))) {
                        throw new IllegalStateException("Chromecast odrzucił uruchomienie YouTube");
                    }
                    if ("CLOSE".equals(p.optString("type"))) {
                        throw new IllegalStateException("Odbiornik zamknął połączenie Cast");
                    }
                }
                if (HEARTBEAT.equals(namespace)) {
                    JSONObject p = new JSONObject(payload);
                    if ("PING".equals(p.optString("type"))) send(out, RECEIVER, HEARTBEAT, "{\"type\":\"PONG\"}");
                }
                if (!requestSent) {
                    send(out, RECEIVER, YOUTUBE_MDX,
                            "{\"type\":\"getMdxSessionStatus\",\"requestId\":2}");
                    requestSent = true;
                }
                if (YOUTUBE_MDX.equals(namespace)) {
                    JSONObject p = new JSONObject(payload);
                    if ("mdxSessionStatus".equals(p.optString("type"))) {
                        JSONObject data = p.optJSONObject("data");
                        if (data != null) return data.optString("screenId", "");
                    }
                }
            }
        }
        throw new java.net.SocketTimeoutException("Brak odpowiedzi YouTube MDX po 15 sekundach");
    }

    private static String fetchLoungeToken(String screenId) throws Exception {
        JSONObject response = postForm(LOUNGE + "/pairing/get_lounge_token_batch",
                "screen_ids=" + enc(screenId));
        JSONArray screens = response.optJSONArray("screens");
        if (screens == null || screens.length() == 0) throw new IllegalStateException("YouTube nie wydał tokenu Lounge");
        String token = screens.getJSONObject(0).optString("loungeToken", "");
        if (token.isEmpty()) throw new IllegalStateException("Brak loungeToken w odpowiedzi YouTube");
        return token;
    }

    private static LoungeSession bindLounge(String token) throws Exception {
        String query = "CVER=1&RID=1&VER=8&app=youtube-desktop&device=REMOTE_CONTROL&id=remote"
                + "&loungeIdToken=" + enc(token) + "&name=" + enc("Morphe secondary Cast");
        String response = postRaw(LOUNGE + "/bc/bind?" + query, "");
        String sid = findSessionValue(response, "c");
        String gsession = findSessionValue(response, "S");
        if (sid.isEmpty() || gsession.isEmpty()) {
            throw new IllegalStateException("YouTube Lounge nie utworzył sesji (brak SID/gsessionid)");
        }
        return new LoungeSession(sid, gsession);
    }

    private static void playVideo(LoungeSession session, String token, String videoId) throws Exception {
        String query = "CVER=1&RID=2&VER=8&SID=" + enc(session.sid)
                + "&gsessionid=" + enc(session.gsessionId)
                + "&loungeIdToken=" + enc(token) + "&name=" + enc("Morphe secondary Cast")
                + "&device=REMOTE_CONTROL&id=remote";
        String body = "count=1&req0__sc=setPlaylist&req0_currentIndex=0&req0_currentTime=0"
                + "&req0_videoId=" + enc(videoId) + "&req0_videoIds=" + enc(videoId)
                + "&req0_listId=&req0_audioOnly=false";
        postRaw(LOUNGE + "/bc/bind?" + query, body);
    }

    private static JSONObject postForm(String url, String form) throws Exception {
        String body = postRaw(url, form);
        return new JSONObject(body);
    }

    private static String postRaw(String address, String body) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(12000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36");
        connection.setFixedLengthStreamingMode(data.length);
        try (OutputStream out = connection.getOutputStream()) { out.write(data); }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String response = input == null ? "" : readText(input);
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IllegalStateException("YouTube Lounge HTTP " + status + ": " + response);
        return response;
    }

    private static String readText(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String findSessionValue(String response, String key) {
        // BrowserChannel frames are length-prefixed lines followed by JSON arrays.
        String compact = response.replaceAll("\\s+", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\[\"" + key + "\",\"([^\"]+)\"").matcher(compact);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static void send(DataOutputStream out, String destination, String namespace, String json) throws Exception {
        ByteArrayOutputStream protobuf = new ByteArrayOutputStream();
        writeVarintField(protobuf, 1, 0);
        writeStringField(protobuf, 2, SOURCE);
        writeStringField(protobuf, 3, destination);
        writeStringField(protobuf, 4, namespace);
        writeVarintField(protobuf, 5, 0);
        writeStringField(protobuf, 6, json);
        byte[] bytes = protobuf.toByteArray();
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    private static JSONObject readMessage(DataInputStream in) throws Exception {
        int length = in.readInt();
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
            } else throw new IllegalStateException("Nieobsługiwany typ pola Cast: " + wire);
        }
        if (payload == null) return null;
        JSONObject message = new JSONObject();
        message.put("namespace", namespace == null ? "" : namespace);
        message.put("payload", payload);
        return message;
    }

    // Low 32 bits contain value; high 32 bits contain next offset.
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

    private static final class LoungeSession {
        final String sid;
        final String gsessionId;
        LoungeSession(String sid, String gsessionId) {
            this.sid = sid;
            this.gsessionId = gsessionId;
        }
    }
}
