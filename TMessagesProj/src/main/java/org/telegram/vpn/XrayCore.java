package org.telegram.vpn;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import libXray.LibXray;

/**
 * Thin wrapper around the libXray Go core.
 *
 * The core is driven by a single JSON entry point: every call is
 * {"apiVersion":3,"method":"...","payload":{...}} and every answer is
 * {"success":bool,"data":...,"error":"..."}. Keeping that in one place means the
 * rest of the feature never touches the JNI boundary directly.
 */
public final class XrayCore {

    private static final String TAG = "XrayCore";
    private static final int API_VERSION = 3;

    private static volatile boolean running;
    private static volatile boolean unavailable;
    private static volatile String unavailableReason;

    private XrayCore() {
    }

    /**
     * Loads the Go core exactly once. On a device where the native library cannot
     * load (unsupported ABI, missing dependency) this returns false instead of
     * letting an UnsatisfiedLinkError escape and kill the app. Every public entry
     * checks it, so the VPN page degrades to "core unavailable" rather than crash.
     */
    private static synchronized boolean ensureLoaded() {
        if (unavailable) return false;
        try {
            LibXray.touch();
            return true;
        } catch (Throwable t) {
            unavailable = true;
            unavailableReason = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            Log.e(TAG, "libXray unavailable on this device", t);
            return false;
        }
    }

    public static boolean isAvailable() {
        return ensureLoaded();
    }

    public static String unavailableReason() {
        ensureLoaded();
        return unavailableReason;
    }

    private static JSONObject call(String method, JSONObject payload) {
        if (!ensureLoaded()) {
            return null;
        }
        try {
            JSONObject request = new JSONObject();
            request.put("apiVersion", API_VERSION);
            request.put("method", method);
            if (payload != null) {
                request.put("payload", payload);
            }
            String raw = LibXray.invoke(request.toString());
            if (TextUtils.isEmpty(raw)) {
                Log.w(TAG, method + ": empty answer");
                return null;
            }
            JSONObject response = new JSONObject(raw);
            if (!response.optBoolean("success", false)) {
                Log.w(TAG, method + ": " + response.optString("error"));
                return null;
            }
            return response;
        } catch (Throwable t) {
            Log.e(TAG, method + " failed", t);
            return null;
        }
    }

    /** Turns one share link into an Xray config, or null when the link is unusable. */
    public static String convertLink(String link) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("text", link);
        } catch (Exception ignored) {
        }
        JSONObject response = call("convertShareLinksToXrayJson", payload);
        if (response == null) {
            return null;
        }
        Object data = response.opt("data");
        if (data instanceof JSONObject) {
            return data.toString();
        }
        return data instanceof String ? (String) data : null;
    }

    /** Decodes a base64 subscription body. Returns the raw text on failure. */
    public static String decodeSubscription(String text) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("text", text);
        } catch (Exception ignored) {
        }
        JSONObject response = call("decodeSubscriptionText", payload);
        if (response == null) {
            return text;
        }
        Object data = response.opt("data");
        if (data instanceof JSONObject) {
            return ((JSONObject) data).optString("text", text);
        }
        return data instanceof String ? (String) data : text;
    }

    public static boolean run(String config) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("xrayJson", config);
        } catch (Exception ignored) {
        }
        running = call("runXray", payload) != null;
        return running;
    }

    public static void stop() {
        if (!ensureLoaded()) {
            running = false;
            return;
        }
        call("stopXray", null);
        running = false;
    }

    public static boolean isRunning() {
        return running;
    }

    public static String version() {
        JSONObject response = call("xrayVersion", null);
        if (response == null) {
            return "";
        }
        Object data = response.opt("data");
        if (data instanceof JSONObject) {
            return ((JSONObject) data).optString("version", "");
        }
        return "";
    }
}
