package org.telegram.vpn;

import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;

import java.util.ArrayList;
import java.util.List;

/** Persistence for the server list, subscription metadata and per-group options. */
public final class VpnStore {
    private static final String KEY_SERVERS = "vpn_servers";
    private static final String KEY_SUBSCRIPTION = "vpn_subscription_url";
    private static final String KEY_SUBSCRIPTION_NAME = "vpn_subscription_name";
    private static final String KEY_TRAFFIC = "vpn_subscription_traffic";
    private static final String KEY_EXPIRES = "vpn_subscription_expires";
    private static final String KEY_LAST_SERVER = "vpn_last_server";
    private static final String KEY_GROUP_PREFIX = "vpn_group_";
    private static final int SORT_UNSORTED = 0;
    private static final int SORT_PING = 1;
    private static final int SORT_NAME = 2;

    private VpnStore() {
    }

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public static List<VpnServer> loadServers() {
        List<VpnServer> out = new ArrayList<>();
        try {
            String raw = prefs().getString(KEY_SERVERS, "");
            if (TextUtils.isEmpty(raw)) return out;
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) out.add(VpnServer.fromJson(item));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void saveServers(List<VpnServer> servers) {
        JSONArray array = new JSONArray();
        for (VpnServer server : servers) array.put(server.toJson());
        prefs().edit().putString(KEY_SERVERS, array.toString()).apply();
    }

    public static void upsert(VpnServer server) {
        List<VpnServer> servers = loadServers();
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).id.equals(server.id)) {
                servers.set(i, server);
                saveServers(servers);
                return;
            }
        }
        servers.add(server);
        saveServers(servers);
    }

    public static void remove(String id) {
        List<VpnServer> servers = loadServers();
        for (int i = servers.size() - 1; i >= 0; i--) {
            if (servers.get(i).id.equals(id)) {
                servers.remove(i);
                saveServers(servers);
                return;
            }
        }
    }

    public static void remove(List<String> ids) {
        List<VpnServer> servers = loadServers();
        for (int i = servers.size() - 1; i >= 0; i--) {
            if (ids.contains(servers.get(i).id)) servers.remove(i);
        }
        saveServers(servers);
    }

    public static void removeBySubscription(String subscription) {
        if (subscription == null) return;
        List<VpnServer> servers = loadServers();
        for (int i = servers.size() - 1; i >= 0; i--) {
            if (subscription.equals(servers.get(i).subscription)) servers.remove(i);
        }
        saveServers(servers);
    }

    public static void removeStale(String subscription, String freshLinks) {
        if (subscription == null || freshLinks == null) return;
        java.util.Set<String> fresh = new java.util.HashSet<>();
        for (String line : freshLinks.split("\\r?\\n")) fresh.add(line.trim());
        List<VpnServer> servers = loadServers();
        VpnServer active = VpnController.getCurrent();
        String activeId = active != null && VpnController.isConnected() ? active.id : null;
        boolean changed = false;
        for (int i = servers.size() - 1; i >= 0; i--) {
            VpnServer server = servers.get(i);
            if (server.id.equals(activeId)) continue;
            if (subscription.equals(server.subscription) && !fresh.contains(server.link)) {
                servers.remove(i);
                changed = true;
            }
        }
        if (changed) saveServers(servers);
    }

    public static String getSubscriptionUrl() {
        return prefs().getString(KEY_SUBSCRIPTION, "");
    }

    public static void setSubscriptionUrl(String url) {
        prefs().edit().putString(KEY_SUBSCRIPTION, TextUtils.isEmpty(url) ? "" : url).apply();
    }

    public static String getSubscriptionName() {
        String name = prefs().getString(KEY_SUBSCRIPTION_NAME, "");
        if (!TextUtils.isEmpty(name)) return name;
        String url = getSubscriptionUrl();
        return TextUtils.isEmpty(url) ? "Подписка" : Uri.parse(url).getHost();
    }

    public static void setSubscriptionName(String name) {
        prefs().edit().putString(KEY_SUBSCRIPTION_NAME, TextUtils.isEmpty(name) ? "" : name).apply();
    }

    public static String getTrafficLabel() {
        return prefs().getString(KEY_TRAFFIC, "0B / ∞");
    }

    public static void setTrafficLabel(String label) {
        prefs().edit().putString(KEY_TRAFFIC, TextUtils.isEmpty(label) ? "0B / ∞" : label).apply();
    }

    public static long getExpiry() {
        return prefs().getLong(KEY_EXPIRES, 0L);
    }

    public static void setExpiry(long timestamp) {
        prefs().edit().putLong(KEY_EXPIRES, timestamp).apply();
    }

    public static String getLastServerId() {
        return prefs().getString(KEY_LAST_SERVER, "");
    }

    public static void setLastServerId(String id) {
        prefs().edit().putString(KEY_LAST_SERVER, id == null ? "" : id).apply();
    }

    public static boolean isPingOnOpen(boolean subscription) {
        return prefs().getBoolean(KEY_GROUP_PREFIX + group(subscription) + "_ping", false);
    }

    public static void setPingOnOpen(boolean subscription, boolean value) {
        prefs().edit().putBoolean(KEY_GROUP_PREFIX + group(subscription) + "_ping", value).apply();
    }

    public static boolean isConnectOnOpen(boolean subscription) {
        return prefs().getBoolean(KEY_GROUP_PREFIX + group(subscription) + "_connect", false);
    }

    public static void setConnectOnOpen(boolean subscription, boolean value) {
        prefs().edit().putBoolean(KEY_GROUP_PREFIX + group(subscription) + "_connect", value).apply();
    }

    public static boolean isUpdateOnOpen() {
        return prefs().getBoolean(KEY_GROUP_PREFIX + "sub_update", false);
    }

    public static void setUpdateOnOpen(boolean value) {
        prefs().edit().putBoolean(KEY_GROUP_PREFIX + "sub_update", value).apply();
    }

    public static boolean isCollapseAllowed(boolean subscription) {
        return prefs().getBoolean(KEY_GROUP_PREFIX + group(subscription) + "_collapse", true);
    }

    public static void setCollapseAllowed(boolean subscription, boolean value) {
        prefs().edit().putBoolean(KEY_GROUP_PREFIX + group(subscription) + "_collapse", value).apply();
    }

    public static int getConnectMode(boolean subscription) {
        return prefs().getInt(KEY_GROUP_PREFIX + group(subscription) + "_connect_mode", 0);
    }

    public static void setConnectMode(boolean subscription, int mode) {
        prefs().edit().putInt(KEY_GROUP_PREFIX + group(subscription) + "_connect_mode", mode).apply();
    }

    public static int getSortMode(boolean subscription) {
        return prefs().getInt(KEY_GROUP_PREFIX + group(subscription) + "_sort", SORT_UNSORTED);
    }

    public static void setSortMode(boolean subscription, int mode) {
        prefs().edit().putInt(KEY_GROUP_PREFIX + group(subscription) + "_sort", mode).apply();
    }

    private static String group(boolean subscription) {
        return subscription ? "sub" : "manual";
    }
}
