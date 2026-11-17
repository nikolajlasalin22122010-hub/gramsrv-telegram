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

    private XrayCore() {
    }

    private static JSONObject call(String method, JSONObject payload) {
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
