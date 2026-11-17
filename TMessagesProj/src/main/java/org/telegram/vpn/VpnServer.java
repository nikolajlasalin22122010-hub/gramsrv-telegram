package org.telegram.vpn;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** One entry in the VPN list: a share link plus the config the core built from it. */
public class VpnServer {
    public final String id;
    public String name;
    public String link;
    public String config;
    /** Subscription URL, or null when the server was added by hand. */
    public String subscription;
    /** Last measured latency in ms, -1 when unknown. Not persisted. */
    public volatile int ping = -1;

    public VpnServer(String id, String name, String link, String config, String subscription) {
        this.id = id;
        this.name = name;
        this.link = link;
        this.config = config;
        this.subscription = subscription;
    }

    public boolean isManual() {
        return TextUtils.isEmpty(subscription);
    }

    public String protocolLabel() {
        return scheme().toUpperCase(Locale.US);
    }

    /** Protocol / transport / security, as displayed under a Happ server name. */
    public String detailsLabel() {
        String protocol = protocolLabel();
        String network = query("type", configTransport());
        String security = query("security", configSecurity());
        if (TextUtils.isEmpty(network)) network = protocol;
        if (TextUtils.isEmpty(security)) security = "TLS";
        return protocol + " / " + capitalize(network) + " / " + capitalize(security);
    }

    /** Regional-indicator flag when the provider included it in the name. */
    public String flag() {
        if (!TextUtils.isEmpty(name)) {
            for (int i = 0; i < name.length(); ) {
                int first = name.codePointAt(i);
                int next = i + Character.charCount(first);
                if (first >= 0x1F1E6 && first <= 0x1F1FF && next < name.length()) {
                    int second = name.codePointAt(next);
                    if (second >= 0x1F1E6 && second <= 0x1F1FF) {
                        return new String(Character.toChars(first)) + new String(Character.toChars(second));
                    }
                }
                i = next;
            }
        }
        String country = query("flag", "");
        if (TextUtils.isEmpty(country)) country = query("country", "");
        String flag = flagForCountry(country);
        if (!TextUtils.isEmpty(flag)) return flag;
        return flagFromName(name);
    }

    private String scheme() {
        if (link != null) {
            int idx = link.indexOf("://");
            if (idx > 0) return link.substring(0, idx);
        }
        return "vless";
    }

    private String query(String key, String fallback) {
        try {
            String value = Uri.parse(link).getQueryParameter(key);
            return TextUtils.isEmpty(value) ? fallback : value;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String configTransport() {
        try {
            JSONObject json = new JSONObject(config);
            String protocol = json.optString("protocol", "");
            JSONObject settings = json.optJSONObject("settings");
            if (settings != null && "vmess".equals(protocol)) {
                return settings.optString("net", "tcp");
            }
            JSONObject stream = json.optJSONObject("streamSettings");
            return stream == null ? "" : stream.optString("network", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String configSecurity() {
        try {
            JSONObject stream = new JSONObject(config).optJSONObject("streamSettings");
            return stream == null ? "" : stream.optString("security", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String capitalize(String value) {
        if (TextUtils.isEmpty(value)) return "";
        return value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1);
    }

    private static String flagFromName(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String lower = value.toLowerCase(Locale.US);
        String[][] names = {
                {"netherlands", "nl"}, {"голланд", "nl"}, {"germany", "de"}, {"герман", "de"},
                {"france", "fr"}, {"франц", "fr"}, {"united states", "us"}, {"usa", "us"},
                {"сша", "us"}, {"united kingdom", "gb"}, {"britain", "gb"}, {"england", "gb"},
                {"sweden", "se"}, {"швец", "se"}, {"switzerland", "ch"}, {"швейцар", "ch"},
                {"finland", "fi"}, {"финлянд", "fi"}, {"poland", "pl"}, {"польш", "pl"},
                {"italy", "it"}, {"итал", "it"}, {"spain", "es"}, {"испан", "es"},
                {"turkey", "tr"}, {"турц", "tr"}, {"singapore", "sg"}, {"сингапур", "sg"},
                {"japan", "jp"}, {"япон", "jp"}, {"europe", "eu"}, {"европ", "eu"}, {"eu ", "eu"}
        };
        for (String[] pair : names) {
            if (lower.contains(pair[0])) return flagForCountry(pair[1]);
        }
        return "";
    }

    private static String flagForCountry(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.US);
        if ("EU".equals(code) || "EUR".equals(code) || "EUROPE".equals(code)) return "\uD83C\uDDEA\uD83C\uDDFA";
        if (code.startsWith(" RU") || "RUSSIA".equals(code)) code = "RU";
        if ("GB".equals(code) || "UK".equals(code)) code = "GB";
        if (code.length() == 2 && code.matches("[A-Z]{2}")) {
            return new String(Character.toChars(0x1F1E6 + code.charAt(0) - 'A'))
                    + new String(Character.toChars(0x1F1E6 + code.charAt(1) - 'A'));
        }
        return "";
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("link", link);
            json.put("config", config);
            json.put("subscription", subscription == null ? JSONObject.NULL : subscription);
        } catch (JSONException ignored) {
        }
        return json;
    }

    public static VpnServer fromJson(JSONObject json) {
        String subscription = json.isNull("subscription") ? null : json.optString("subscription", null);
        return new VpnServer(
                json.optString("id"),
                json.optString("name"),
                json.optString("link"),
                json.optString("config"),
                subscription);
    }
}
