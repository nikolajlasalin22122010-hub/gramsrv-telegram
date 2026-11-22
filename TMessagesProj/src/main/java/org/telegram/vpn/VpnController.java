package org.telegram.vpn;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Owns the tunnel lifecycle.
 *
 * The core runs inside this process and exposes a SOCKS5 listener on the
 * loopback interface; the client then talks to the network through it. Nothing
 * outside the app is routed, and no system VpnService is taken, so a VPN the
 * user runs for other apps keeps working.
 */
public final class VpnController {

    private static final String TAG = "VpnController";

    /**
     * Loopback port the core listens on.
     *
     * Deliberately not 1080 and not 10808: the popular Android VPN clients bind
     * 10808 for their own SOCKS listener, and two apps cannot share it. 443 is
     * out too, it needs root to bind. A high odd port keeps us clear of all of
     * them.
     */
    public static final int SOCKS_PORT = 23871;

    private static volatile VpnServer current;
    private static volatile String lastError;
    private static volatile long connectedSince;
    private static volatile boolean autoConnectAttempted;
    private static volatile Boolean coreAvailable;
    private static final Object CORE_CHECK_LOCK = new Object();
    private static final java.util.concurrent.ExecutorService AUTO_QUEUE =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private VpnController() {
    }

    /**
     * Connects on client launch (not only when the VPN page is opened), mirroring
     * how Telegram auto-applies an MTProto proxy at startup. Runs once per
     * process, off the UI thread, and only when the user has servers configured.
     */
    public static void autoConnectOnLaunch() {
        if (autoConnectAttempted || !VpnStore.isAutoConnectOnLaunch()) return;
        autoConnectAttempted = true;
        AUTO_QUEUE.execute(() -> {
            try {
                isCoreAvailable();
                if (isConnected()) return;
                java.util.List<VpnServer> servers = VpnStore.loadServers();
                if (servers.isEmpty()) return;
                VpnServer target = null;
                String last = VpnStore.getLastServerId();
                for (VpnServer server : servers) {
                    if (server.id.equals(last)) { target = server; break; }
                }
                if (target == null) target = servers.get(0);
                if (VpnStore.isAutoSelectEnabled()) {
                    VpnServer best = autoSelect(servers);
                    if (best != null) target = best;
                }
                connect(target);
            } catch (Throwable t) {
                Log.e(TAG, "auto connect on launch failed", t);
            }
        });
    }

    /**
     * Probes every server and returns the reachable one with the lowest latency,
     * like Telegram's automatic proxy switching. Returns null when none answers.
     */
    public static VpnServer autoSelect(java.util.List<VpnServer> servers) {
        if (servers == null || servers.isEmpty()) return null;
        VpnServer best = null;
        int bestPing = Integer.MAX_VALUE;
        for (VpnServer server : servers) {
            int value = pingServer(server);
            if (value >= 0 && value < bestPing) {
                best = server;
                bestPing = value;
            }
        }
        return best;
    }

    public static boolean isConnected() {
        return current != null && XrayCore.isRunning();
    }

    /**
     * Cached availability probe. The first call loads the native core off the UI
     * thread (auto-connect or an explicit connect); later calls from the UI just
     * read the cached result, so the page never blocks on library loading.
     */
    public static boolean isCoreAvailable() {
        Boolean value = coreAvailable;
        if (value != null) return value;
        synchronized (CORE_CHECK_LOCK) {
            if (coreAvailable == null) {
                coreAvailable = XrayCore.isAvailable();
            }
            return coreAvailable;
        }
    }

    public static VpnServer getCurrent() {
        return current;
    }

    public static String getLastError() {
        return lastError;
    }

    public static long getConnectedSince() {
        return connectedSince;
    }

    /** Connects to one server. Returns null on success, or a human readable error. */
    public static String connect(VpnServer server) {
        if (server == null) {
            return "сервер не выбран";
        }
        if (!isCoreAvailable()) {
            lastError = "VPN-ядро недоступно на этом устройстве";
            return lastError;
        }
        disconnect();
        lastError = null;
        try {
            String config = withSocksInbound(server.config);
            if (!XrayCore.run(config)) {
                lastError = "ядро не запустилось";
                return lastError;
            }
            applyProxy(SOCKS_PORT);
            current = server;
            connectedSince = System.currentTimeMillis();
            VpnStore.setLastServerId(server.id);
            Log.i(TAG, "connected to " + server.name + " on port " + SOCKS_PORT);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "connect failed", e);
            XrayCore.stop();
            clearProxy();
            lastError = e.getMessage() == null ? "ошибка подключения" : e.getMessage();
            return lastError;
        }
    }

    public static void disconnect() {
        XrayCore.stop();
        clearProxy();
        current = null;
        connectedSince = 0;
    }

    /**
     * The core hands back outbounds only, so the SOCKS entry point is added here.
     * Without it there is nothing for the client to dial.
     */
    private static String withSocksInbound(String rawConfig) throws Exception {
        JSONObject config = new JSONObject(rawConfig);
        config.put("log", new JSONObject().put("loglevel", "warning"));

        JSONObject settings = new JSONObject();
        settings.put("auth", "noauth");
        settings.put("udp", true);

        JSONObject inbound = new JSONObject();
        inbound.put("listen", "127.0.0.1");
        inbound.put("port", SOCKS_PORT);
        inbound.put("protocol", "socks");
        inbound.put("tag", "socks-in");
        inbound.put("settings", settings);

        JSONArray inbounds = new JSONArray();
        inbounds.put(inbound);
        config.put("inbounds", inbounds);
        return config.toString();
    }

    private static void applyProxy(int port) {
        ProxySettings settings = ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress("127.0.0.1")
                .setPort(port)
                .setUser("")
                .setPassword("")
                .setSecret("")
                .build();
        SharedConfig.currentProxy = new SharedConfig.ProxyInfo(settings);
        ConnectionsManager.setProxySettings(true, settings);
    }

    private static void clearProxy() {
        SharedConfig.currentProxy = null;
        ConnectionsManager.setProxySettings(false, null);
    }

    /**
     * The core's own subscription decoder hands the text back untouched for
     * base64 bodies, so the decoding happens here. Tries plain base64 first,
     * then the URL-safe alphabet, and falls back to the body itself for
     * subscriptions that are already plain text.
     */
    private static String decodeSubscriptionBody(String body) {
        String compact = body.replaceAll("\\s+", "");
        if (TextUtils.isEmpty(compact)) {
            return body;
        }
        byte[] raw = null;
        try {
            raw = Base64.decode(compact, Base64.DEFAULT);
        } catch (Exception ignored) {
        }
        if (raw == null) {
            try {
                raw = Base64.decode(compact, Base64.URL_SAFE | Base64.NO_PADDING);
            } catch (Exception ignored) {
            }
        }
        if (raw == null) {
            return body;
        }
        String decoded = new String(raw, StandardCharsets.UTF_8);
        // A decoded subscription always carries at least one share link.
        return decoded.contains("://") ? decoded : body;
    }

    /**
     * Downloads a subscription, converts every link and replaces the servers it
     * previously produced. Returns the number of servers, or -1 with lastError set.
     */
    public static int refreshSubscription(String url) {
        try {
            return importSubscription(url, downloadSubscription(url));
        } catch (Exception e) {
            Log.e(TAG, "subscription failed", e);
            lastError = e.getMessage() == null ? "не удалось загрузить подписку" : e.getMessage();
            return -1;
        }
    }

    /** I/O-only phase; the VPN screen runs this outside the serialized core queue. */
    public static String downloadSubscription(String url) throws Exception {
        // Happ fetches subscriptions with certificate validation relaxed; many
        // panels serve a self-signed or mismatched cert, which made the strict
        // default handshake fail with "connection error". Mirror that behaviour:
        // try the normal trust store first, then fall back to a permissive one.
        // The body is only a list of share links, never credentials.
        Exception strictError = null;
        try {
            return downloadSubscriptionOnce(url, false);
        } catch (javax.net.ssl.SSLException e) {
            strictError = e;
        } catch (java.io.IOException e) {
            // Some stacks wrap the handshake failure in a plain IOException.
            if (e.getMessage() != null && (e.getMessage().contains("certificate")
                    || e.getMessage().contains("SSL") || e.getMessage().contains("Trust anchor"))) {
                strictError = e;
            } else {
                throw e;
            }
        }
        Log.w(TAG, "subscription strict TLS failed, retrying permissive", strictError);
        return downloadSubscriptionOnce(url, true);
    }

    private static String downloadSubscriptionOnce(String url, boolean insecure) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        if (insecure && connection instanceof javax.net.ssl.HttpsURLConnection) {
            javax.net.ssl.HttpsURLConnection https = (javax.net.ssl.HttpsURLConnection) connection;
            javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
                @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            }}, new java.security.SecureRandom());
            https.setSSLSocketFactory(context.getSocketFactory());
            https.setHostnameVerifier((hostname, session) -> true);
        }
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Happ/1.0");
            connection.setRequestProperty("Accept", "*/*");
            // Defeat intermediate caches so a refresh always sees fresh servers.
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setUseCaches(false);
            StringBuilder body = new StringBuilder();
            long deadline = android.os.SystemClock.elapsedRealtime() + 45000L;
            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[4096];
                int length;
                while ((length = reader.read(chunk)) != -1) {
                    if (android.os.SystemClock.elapsedRealtime() > deadline || body.length() + length > 4 * 1024 * 1024) {
                        throw new java.io.IOException("подписка слишком большая или загрузка затянулась");
                    }
                    body.append(chunk, 0, length);
                }
            }
            saveSubscriptionMetadata(connection, url, body.toString());
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    /** Parses standard subscription headers and the optional name from Content-Disposition. */
    private static void saveSubscriptionMetadata(HttpURLConnection connection, String url, String body) {
        String name = "";
        String traffic = "0B / ∞";
        long expires = 0;
        String disposition = connection.getHeaderField("Content-Disposition");
        if (!TextUtils.isEmpty(disposition)) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("filename\\*?=(?:UTF-8''|\")?([^;\\\"]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(disposition);
            if (matcher.find()) {
                try {
                    name = URLDecoder.decode(matcher.group(1).trim(), "UTF-8");
                } catch (Exception ignored) {
                }
            }
        }
        String info = connection.getHeaderField("subscription-userinfo");
        if (!TextUtils.isEmpty(info)) {
            double used = 0;
            double total = 0;
            boolean hasTotal = false;
            for (String part : info.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length != 2) continue;
                String key = pair[0].trim().toLowerCase(java.util.Locale.US);
                String value = pair[1].trim().replace("+", " ");
                try {
                    if ("upload".equals(key)) used += Double.parseDouble(value);
                    else if ("download".equals(key)) used += Double.parseDouble(value);
                    else if ("total".equals(key)) {
                        total = Double.parseDouble(value);
                        hasTotal = true;
                    } else if ("expire".equals(key)) {
                        expires = (long) (Double.parseDouble(value) * 1000L);
                    }
                } catch (Exception ignored) {
                }
            }
            String quota = !hasTotal ? "—" : total <= 0 ? "∞" : formatBytes((long) total);
            traffic = (used > 0 ? formatBytes((long) used) : "0B") + " / " + quota;
        }
        if (TextUtils.isEmpty(name)) {
            try {
                String[] lines = body.split("\\r?\\n");
                if (lines.length > 0 && lines[0].startsWith("//") && !lines[0].contains("://")) {
                    name = lines[0].substring(2).trim();
                }
            } catch (Exception ignored) {
            }
        }
        if (TextUtils.isEmpty(name)) name = Uri.parse(url).getHost();
        VpnStore.setSubscriptionName(name);
        VpnStore.setTrafficLabel(traffic);
        VpnStore.setExpiry(expires);
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return (unit == 0 ? String.valueOf((long) value)
                : String.format(java.util.Locale.US, "%.1f", value)) + units[unit];
    }

    /** Core/store phase; parsing failure never clears previously saved servers. */
    public static int importSubscription(String url, String body) {
        lastError = null;
        if (!isCoreAvailable()) {
            lastError = "VPN-ядро недоступно на этом устройстве";
            return -1;
        }
        try {
            String decoded = decodeSubscriptionBody(body);
            Log.i(TAG, "subscription body " + body.length() + " -> " + decoded.length() + " chars");
            java.util.List<VpnServer> fresh = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String raw : decoded.split("\\r?\\n")) {
                String link = raw.trim();
                if (!isSupportedLink(link)) continue;
                String id = stableId(link);
                if (!seen.add(id)) continue;
                String config = XrayCore.convertLink(link);
                if (TextUtils.isEmpty(config)) continue;
                fresh.add(new VpnServer(id, nameFromLink(link), link, config, url));
            }
            if (fresh.isEmpty()) {
                lastError = "в подписке не нашлось рабочих ссылок";
                return -1;
            }
            // Replace the whole subscription set atomically: upsert-then-prune
            // left stale servers behind when the provider rotated links, because
            // the active server was always kept and ordering drifted. Rebuild
            // instead, preserving only the currently connected server.
            VpnStore.replaceSubscription(url, fresh);
            return fresh.size();
        } catch (Exception e) {
            Log.e(TAG, "subscription import failed", e);
            lastError = e.getMessage() == null ? "не удалось разобрать подписку" : e.getMessage();
            return -1;
        }
    }

    /**
     * Endpoint TCP connection time, not a tunneled RTT or a speed test.
     * java.net.URL rejects vless:// and the other custom share-link schemes.
     */
    public static int pingServer(String link) {
        if (TextUtils.isEmpty(link)) return -1;
        try {
            Uri uri = Uri.parse(link);
            return pingEndpoint(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 443);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Reads the converted endpoint too, including base64 VMess/SS share links. */
    public static int pingServer(VpnServer server) {
        if (server == null) return -1;
        try {
            JSONArray outbounds = new JSONObject(server.config).optJSONArray("outbounds");
            if (outbounds != null) {
                for (int i = 0; i < outbounds.length(); i++) {
                    JSONObject outbound = outbounds.optJSONObject(i);
                    JSONObject settings = outbound == null ? null : outbound.optJSONObject("settings");
                    if (settings == null) continue;
                    JSONArray endpoints = settings.optJSONArray("vnext");
                    if (endpoints == null) endpoints = settings.optJSONArray("servers");
                    if (endpoints == null || endpoints.length() == 0) continue;
                    JSONObject endpoint = endpoints.optJSONObject(0);
                    if (endpoint == null) continue;
                    String host = endpoint.optString("address", "");
                    int port = endpoint.optInt("port", 443);
                    if (!TextUtils.isEmpty(host)) return pingEndpoint(host, port);
                }
            }
        } catch (Exception ignored) {
        }
        return pingServer(server.link);
    }

    private static int pingEndpoint(String host, int port) {
        if (TextUtils.isEmpty(host) || port < 1 || port > 65535) return -1;
        try {
            // Monotonic time is not affected by clock changes during the probe.
            long started = android.os.SystemClock.elapsedRealtime();
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 1500);
            }
            return (int) Math.max(0, android.os.SystemClock.elapsedRealtime() - started);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Adds one hand-pasted link. Returns null on success. */
    public static String addLink(String link) {
        link = link == null ? "" : link.trim();
        if (!isSupportedLink(link)) {
            return "это не похоже на ссылку vless://, vmess://, trojan:// или ss://";
        }
        if (!isCoreAvailable()) {
            return "VPN-ядро недоступно на этом устройстве";
        }
        String config = XrayCore.convertLink(link);
        if (TextUtils.isEmpty(config)) {
            return "ядро не смогло разобрать ссылку";
        }
        VpnStore.upsert(new VpnServer(stableId(link), nameFromLink(link), link, config, null));
        return null;
    }

    private static boolean isSupportedLink(String link) {
        return link.startsWith("vless://") || link.startsWith("vmess://")
                || link.startsWith("trojan://") || link.startsWith("ss://");
    }

    private static String stableId(String link) {
        return UUID.nameUUIDFromBytes(link.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** The fragment after # is the display name the provider chose. */
    private static String nameFromLink(String link) {
        int hash = link.indexOf('#');
        if (hash >= 0 && hash + 1 < link.length()) {
            try {
                String name = URLDecoder.decode(link.substring(hash + 1), "UTF-8").trim();
                if (!name.isEmpty()) {
                    return name;
                }
            } catch (Exception ignored) {
            }
        }
        try {
            Uri uri = Uri.parse(link);
            String host = uri.getHost();
            if (!TextUtils.isEmpty(host)) {
                return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
            }
        } catch (Exception ignored) {
        }
        return "Сервер";
    }
}
