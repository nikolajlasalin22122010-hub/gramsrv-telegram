package org.telegram.vpn;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VPN screen in the Hiddify style: the home page with the round connection
 * button over a faint world map, the active profile card with its traffic bar,
 * and the proxy list with a flag, the protocol line and the ping on the right.
 * Every control has a fixed dp size, so nothing spills on any screen.
 */
public class VpnFragment extends BaseFragment {
    private static final int BG = 0xFF121212;
    private static final int CARD = 0xFF1E1F22;
    private static final int CARD_ACTIVE = 0xFF26283A;
    private static final int TRACK = 0xFF35363C;
    private static final int TEXT = 0xFFECECF1;
    private static final int MUTED = 0xFF9C9CA6;
    private static final int ACCENT = 0xFF8C9EFF;
    private static final int GREEN = 0xFF7ED957;
    private static final int AMBER = 0xFFFFB74D;
    private static final int RED = 0xFFFF6E6E;
    private static final int OUTLINE = 0x33FFFFFF;
    private static final ExecutorService CORE_QUEUE = Executors.newSingleThreadExecutor();
    private static final ExecutorService IO_QUEUE = Executors.newFixedThreadPool(2);
    private static final AtomicInteger STORE_REVISION = new AtomicInteger();
    private static volatile boolean connectionBusy;
    private static volatile boolean subscriptionBusy;
    private Context context;
    private final ExecutorService probes = Executors.newFixedThreadPool(4);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<VpnServer> manualServers = new ArrayList<>();
    private final List<VpnServer> subscriptionServers = new ArrayList<>();
    private final Map<String, Integer> latency = new HashMap<>();
    private final Set<String> selectedForDeletion = new HashSet<>();
    private VpnPowerView powerView;
    private ListView list;
    private TextView statusText, statusSub;
    private TextView subTitle, subTraffic, subExpiry;
    private TrafficBar trafficBar;
    private VpnIcon subChevron, subRefresh, subMore, manualChevron, manualMore;
    private LinearLayout subCard, manualCard, emptyHint, subRows, manualRows;
    private VpnIcon autoSelectButton;
    private String selectedId;
    private boolean subCollapsed, manualCollapsed, probing, multiSelect, destroyed;
    private boolean lastConnected;
    private boolean listDirty;
    private int storeRevision;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            if (storeRevision != STORE_REVISION.get()) reload();
            boolean connected = VpnController.isConnected();
            if (connected != lastConnected || listDirty) renderList();
            else updateConnection();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public View createView(Context context) {
        this.context = context;
        selectedId = VpnStore.getLastServerId();
        actionBar.setBackButtonDrawable(new VpnIconDrawable(context, VpnIcon.ARROW_BACK, TEXT));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle("DaskGram VPN");
        actionBar.setBackgroundColor(BG);
        actionBar.setTitleColor(TEXT);
        actionBar.setItemsColor(TEXT, false);
        actionBar.createMenu().addItem(1, new VpnIconDrawable(context, VpnIcon.SETTINGS, TEXT)).setContentDescription("Настройки VPN");
        actionBar.createMenu().addItem(2, new VpnIconDrawable(context, VpnIcon.ADD, ACCENT)).setContentDescription("Добавить");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == 1) showGeneralSettings();
                else if (id == 2) showAddMenu();
            }
        });

        VpnBackdropView frame = new VpnBackdropView(context);
        fragmentView = frame;
        frame.setBackgroundColor(BG);
        list = new ListView(context);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, AndroidUtilities.dp(24));
        frame.addView(list, frame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));

        LinearLayout header = column(context);
        list.addHeaderView(header, null, false);

        // The connection button, centred, with its label under it as in Hiddify.
        FrameLayout powerBox = new FrameLayout(context);
        powerView = new VpnPowerView(context);
        powerView.setOnClickListener(v -> toggleConnection());
        powerBox.addView(powerView, frame(200, 200, Gravity.CENTER));
        header.addView(powerBox, lin(LayoutHelper.MATCH_PARENT, 216, 0, 8, 0, 0));
        statusText = text(context, "Нажмите для подключения", 16, TEXT, true);
        statusText.setGravity(Gravity.CENTER);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        statusText.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        header.addView(statusText, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        statusSub = text(context, "", 12, MUTED, false);
        statusSub.setGravity(Gravity.CENTER);
        statusSub.setSingleLine(true);
        statusSub.setEllipsize(TextUtils.TruncateAt.END);
        statusSub.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4), AndroidUtilities.dp(24), AndroidUtilities.dp(16));
        header.addView(statusSub, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Manual group sits ABOVE the subscription, as in Happ. The card and its
        // server rows share one container so the border wraps the whole group and
        // expands together with the list. Hidden while there are no manual servers.
        manualCard = column(context);
        manualCard.setBackground(surface(CARD, 16, OUTLINE, false));
        LinearLayout manualRow = row(context);
        manualRow.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6));
        TextView manualTitle = text(context, "Вручную", 15, TEXT, true);
        manualRow.addView(manualTitle, linWeight(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        manualChevron = icon(VpnIcon.ARROW_DROP_DOWN, 22);
        manualChevron.setColor(MUTED);
        manualRow.addView(manualChevron, lin(28, 28));
        manualMore = icon(VpnIcon.MORE, 22);
        manualMore.setColor(MUTED);
        manualMore.setOnClickListener(v -> showGroupMenu(false));
        manualMore.setContentDescription("Меню серверов вручную");
        manualRow.addView(manualMore, lin(28, 28, 2, 0, 0, 0));
        manualCard.addView(manualRow, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        manualRows = column(context);
        manualCard.addView(manualRows, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        manualCard.setOnClickListener(v -> {
            if (!VpnStore.isCollapseAllowed(false)) return;
            manualCollapsed = !manualCollapsed;
            renderList();
        });
        header.addView(manualCard, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 8));

        // Subscription group as the Hiddify profile card: refresh on the left,
        // name, traffic bar and the used/expiry line on the right. Its server rows
        // live inside the same card so the border grows with the expanded list.
        subCard = column(context);
        subCard.setBackground(surface(CARD, 16, OUTLINE, false));
        LinearLayout subRow = row(context);
        subRefresh = icon(VpnIcon.SYNC, 22);
        subRefresh.setOnClickListener(v -> refresh());
        subRefresh.setContentDescription("Обновить подписку");
        subRow.addView(subRefresh, lin(48, LayoutHelper.MATCH_PARENT));
        subRow.addView(divider(context, true), lin(1, LayoutHelper.MATCH_PARENT));
        LinearLayout subInfo = column(context);
        subInfo.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10));
        LinearLayout subTitleRow = row(context);
        subTitle = text(context, "Подписка", 15, TEXT, true);
        subTitle.setSingleLine(true);
        subTitle.setEllipsize(TextUtils.TruncateAt.END);
        subTitleRow.addView(subTitle, linWeight(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        subChevron = icon(VpnIcon.ARROW_DROP_DOWN, 22);
        subChevron.setColor(MUTED);
        subTitleRow.addView(subChevron, lin(28, 28, 4, 0, 0, 0));
        subMore = icon(VpnIcon.MORE, 22);
        subMore.setColor(MUTED);
        subMore.setOnClickListener(v -> showGroupMenu(true));
        subMore.setContentDescription("Меню подписки");
        subTitleRow.addView(subMore, lin(28, 28, 2, 0, 0, 0));
        subInfo.addView(subTitleRow, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        trafficBar = new TrafficBar(context);
        subInfo.addView(trafficBar, lin(LayoutHelper.MATCH_PARENT, 6, 0, 8, 0, 6));
        LinearLayout subStats = row(context);
        subTraffic = text(context, "0B / ∞", 11, MUTED, false);
        subTraffic.setSingleLine(true);
        subStats.addView(subTraffic, linWeight(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        subExpiry = text(context, "Истекает: —", 11, MUTED, false);
        subExpiry.setSingleLine(true);
        subStats.addView(subExpiry, lin(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));
        subInfo.addView(subStats, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        subRow.addView(subInfo, linWeight(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        subCard.addView(subRow, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        subRows = column(context);
        subCard.addView(subRows, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        subCard.setOnClickListener(v -> {
            if (!VpnStore.isCollapseAllowed(true)) return;
            subCollapsed = !subCollapsed;
            renderList();
        });
        header.addView(subCard, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 8));

        // Auto-select: probe every server and connect to the first reachable one,
        // like Telegram's automatic proxy switching. Sits under the power button.
        LinearLayout autoRow = row(context);
        autoRow.setGravity(Gravity.CENTER);
        autoRow.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        autoSelectButton = icon(VpnIcon.AUTO_FIX, 20);
        autoSelectButton.setColor(ACCENT);
        autoSelectButton.setOnClickListener(v -> runAutoSelect());
        autoSelectButton.setContentDescription("Автовыбор рабочего сервера");
        autoRow.addView(autoSelectButton, lin(28, 28, 0, 0, 6, 0));
        TextView autoLabel = text(context, "Автовыбор сервера", 13, ACCENT, false);
        autoLabel.setOnClickListener(v -> runAutoSelect());
        autoRow.addView(autoLabel, lin(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        header.addView(autoRow, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Always-visible quick actions. The ActionBar "+" and gear can be hidden by
        // the title on narrow screens, so the same entry points live in the body too
        // and can never be missed.
        LinearLayout quickActions = row(context);
        quickActions.setGravity(Gravity.CENTER);
        quickActions.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(2), AndroidUtilities.dp(24), AndroidUtilities.dp(10));
        TextView addBtn = text(context, "+ Добавить", 14, ACCENT, true);
        addBtn.setBackground(surface(CARD, 12, OUTLINE, false));
        addBtn.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(10), AndroidUtilities.dp(18), AndroidUtilities.dp(10));
        addBtn.setOnClickListener(v -> showAddMenu());
        quickActions.addView(addBtn, lin(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));
        TextView settingsBtn = text(context, "Настройки", 14, TEXT, false);
        settingsBtn.setBackground(surface(CARD, 12, OUTLINE, false));
        settingsBtn.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(10), AndroidUtilities.dp(18), AndroidUtilities.dp(10));
        settingsBtn.setOnClickListener(v -> showGeneralSettings());
        quickActions.addView(settingsBtn, lin(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        header.addView(quickActions, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // One quiet hint instead of two empty cards: shown only when nothing
        // at all is configured, so a fresh screen stays clean.
        emptyHint = column(context);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(AndroidUtilities.dp(32), AndroidUtilities.dp(20), AndroidUtilities.dp(32), AndroidUtilities.dp(8));
        TextView emptyLine = text(context, "Добавьте подписку или сервер кнопкой + сверху", 13, MUTED, false);
        emptyLine.setGravity(Gravity.CENTER);
        emptyHint.addView(emptyLine, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        header.addView(emptyHint, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        reload();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (powerView != null) powerView.setAnimationsEnabled(true);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        reload();
        applyOpenBehavior();
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (powerView != null) powerView.setAnimationsEnabled(false);
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        probes.shutdownNow();
        super.onFragmentDestroy();
    }

    private LinearLayout column(Context context) {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private LinearLayout row(Context context) {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private TextView text(Context context, String value, float size, int color, boolean medium) {
        TextView result = new TextView(context);
        result.setText(value);
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        result.setTextColor(color);
        if (medium) result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return result;
    }

    private VpnIcon icon(String glyph, float sizeDp) {
        VpnIcon result = new VpnIcon(context, glyph);
        result.setColor(ACCENT);
        result.setTextSizeDp(sizeDp);
        result.setBackground(ripple(12));
        return result;
    }

    private View divider(Context context, boolean vertical) {
        View view = new View(context);
        view.setBackgroundColor(OUTLINE);
        return view;
    }

    /**
     * Explicit layout params. The numeric LayoutHelper.createLinear overloads are
     * ambiguous between "margins" and "gravity + margins" depending on argument
     * types, which on some builds collapsed the connection button and the server
     * cards to zero size. These helpers set width/height/margins directly so the
     * page lays out identically on every device.
     */
    private static LinearLayout.LayoutParams lin(int widthDp, int heightDp) {
        return new LinearLayout.LayoutParams(
                widthDp < 0 ? widthDp : AndroidUtilities.dp(widthDp),
                heightDp < 0 ? heightDp : AndroidUtilities.dp(heightDp));
    }

    private static LinearLayout.LayoutParams lin(int widthDp, int heightDp, int leftDp, int topDp, int rightDp, int bottomDp) {
        LinearLayout.LayoutParams p = lin(widthDp, heightDp);
        p.setMargins(AndroidUtilities.dp(leftDp), AndroidUtilities.dp(topDp), AndroidUtilities.dp(rightDp), AndroidUtilities.dp(bottomDp));
        return p;
    }

    private static LinearLayout.LayoutParams linWeight(int widthDp, int heightDp, float weight, int leftDp, int topDp, int rightDp, int bottomDp) {
        LinearLayout.LayoutParams p = lin(widthDp, heightDp);
        p.weight = weight;
        p.setMargins(AndroidUtilities.dp(leftDp), AndroidUtilities.dp(topDp), AndroidUtilities.dp(rightDp), AndroidUtilities.dp(bottomDp));
        return p;
    }

    private static FrameLayout.LayoutParams frame(int widthDp, int heightDp, int gravity) {
        return new FrameLayout.LayoutParams(
                widthDp < 0 ? widthDp : AndroidUtilities.dp(widthDp),
                heightDp < 0 ? heightDp : AndroidUtilities.dp(heightDp), gravity);
    }

    /**
     * Dark palette for every AlertDialog on this screen: the stock dialog is
     * white, which made the menu rows and switches nearly invisible against
     * the dark page. The provider overrides only the dialog color keys.
     */
    private final Theme.ResourcesProvider dialogTheme = new Theme.ResourcesProvider() {
        @Override
        public int getColor(int key) {
            if (key == Theme.key_dialogBackground) return CARD;
            if (key == Theme.key_dialogTextBlack) return TEXT;
            if (key == Theme.key_dialogTextGray2 || key == Theme.key_dialogTextGray3) return MUTED;
            if (key == Theme.key_dialogTextLink || key == Theme.key_dialogButton) return ACCENT;
            if (key == Theme.key_dialogIcon) return MUTED;
            if (key == Theme.key_dialogButtonSelector) return 0x1F8C9EFF;
            if (key == Theme.key_dialogScrollGlow) return ACCENT;
            if (key == Theme.key_dialogLineProgress) return ACCENT;
            if (key == Theme.key_dialogLineProgressBackground) return TRACK;
            if (key == Theme.key_windowBackgroundWhiteGrayText) return MUTED;
            if (key == Theme.key_windowBackgroundWhiteBlackText) return TEXT;
            if (key == Theme.key_windowBackgroundWhiteHintText) return MUTED;
            if (key == Theme.key_windowBackgroundWhiteInputField) return TRACK;
            if (key == Theme.key_windowBackgroundWhiteInputFieldActivated) return ACCENT;
            return Theme.getColor(key);
        }
    };

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(getParentActivity(), dialogTheme);
    }

    /** A ListView that reports its full content height, so a menu dialog shows
     * every row at once instead of a one-row window with a hidden scroll. */
    private class MenuListView extends ListView {
        MenuListView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int expand = MeasureSpec.makeMeasureSpec(Integer.MAX_VALUE >> 2, MeasureSpec.AT_MOST);
            super.onMeasure(widthMeasureSpec, expand);
            int wanted = getMeasuredHeight();
            int cap = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
            if (wanted > cap) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY));
            } else {
                setMeasuredDimension(getMeasuredWidth(), wanted);
            }
        }
    }

    private EditText darkInput(String hint) {
        EditText input = new EditText(getParentActivity());
        input.setHint(hint);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setBackground(surface(TRACK, 10, Color.TRANSPARENT, false));
        input.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        return input;
    }

    private void styleSwitch(Switch toggle) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}};
        toggle.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, 0xFFB9B9C4}));
        toggle.setTrackTintList(new ColorStateList(states, new int[]{0x668C9EFF, 0xFF3A3B42}));
    }

    private void ui(Runnable work) {
        handler.post(() -> { if (!destroyed) work.run(); });
    }

    private void toast(String message) {
        if (!destroyed && getParentActivity() != null) Toast.makeText(getParentActivity(), message, Toast.LENGTH_SHORT).show();
    }

    private VpnServer selectedServer() {
        for (VpnServer server : allServers()) if (server.id.equals(selectedId)) return server;
        return null;
    }

    private List<VpnServer> allServers() {
        List<VpnServer> all = new ArrayList<>(subscriptionServers);
        all.addAll(manualServers);
        return all;
    }

    private void reload() {
        if (destroyed) return;
        storeRevision = STORE_REVISION.get();
        selectedId = VpnStore.getLastServerId();
        manualServers.clear();
        subscriptionServers.clear();
        for (VpnServer server : VpnStore.loadServers()) {
            if (server.isManual()) manualServers.add(server);
            else subscriptionServers.add(server);
        }
        sortGroup(false, manualServers);
        sortGroup(true, subscriptionServers);
        if (selectedServer() == null && !allServers().isEmpty()) {
            VpnServer current = VpnController.getCurrent();
            selectedId = current == null ? allServers().get(0).id : current.id;
            if (selectedServer() == null) selectedId = allServers().get(0).id;
            VpnStore.setLastServerId(selectedId);
        }
        renderList();
        updateSubscription();
    }

    private void sortGroup(boolean subscription, List<VpnServer> group) {
        int mode = VpnStore.getSortMode(subscription);
        if (mode == 1) Collections.sort(group, (a, b) -> Integer.compare(latencyOrder(a), latencyOrder(b)));
        else if (mode == 2) Collections.sort(group, (a, b) -> a.name.compareToIgnoreCase(b.name));
    }

    private int latencyOrder(VpnServer server) {
        Integer value = latency.get(server.id);
        return value == null || value < 0 ? Integer.MAX_VALUE : value;
    }

    private void renderList() {
        if (destroyed || subRows == null || manualRows == null) return;
        listDirty = false;
        subChevron.setText(subCollapsed ? VpnIcon.CHEVRON_RIGHT : VpnIcon.ARROW_DROP_DOWN);
        manualChevron.setText(manualCollapsed ? VpnIcon.CHEVRON_RIGHT : VpnIcon.ARROW_DROP_DOWN);
        // Empty groups take no space: the subscription card shows only with a
        // saved URL or servers, the manual card only with servers.
        boolean hasSubscription = !subscriptionServers.isEmpty() || !TextUtils.isEmpty(VpnStore.getSubscriptionUrl());
        subCard.setVisibility(hasSubscription ? View.VISIBLE : View.GONE);
        manualCard.setVisibility(manualServers.isEmpty() ? View.GONE : View.VISIBLE);
        emptyHint.setVisibility(!hasSubscription && manualServers.isEmpty() ? View.VISIBLE : View.GONE);
        subRefresh.setEnabled(!subscriptionBusy);
        subRefresh.setAlpha(subscriptionBusy ? 0.4f : 1f);
        // Server rows live inside their group card so the border wraps the whole
        // group and expands together with the list.
        subRows.removeAllViews();
        manualRows.removeAllViews();
        if (!subCollapsed) {
            for (VpnServer server : subscriptionServers) subRows.addView(buildServerRow(server, true));
        }
        if (!manualCollapsed) {
            for (VpnServer server : manualServers) manualRows.addView(buildServerRow(server, false));
        }
        updateConnection();
    }

    private void updateSubscription() {
        if (subTitle == null) return;
        subTitle.setText(subscriptionBusy ? "Обновление подписки…" : VpnStore.getSubscriptionName());
        String traffic = VpnStore.getTrafficLabel();
        subTraffic.setText(traffic);
        trafficBar.setRatio(trafficRatio(traffic));
        long expiry = VpnStore.getExpiry();
        if (expiry > 0) {
            boolean expired = expiry < System.currentTimeMillis();
            subExpiry.setText(expired ? "Истекла"
                    : "Истекает: " + new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date(expiry)));
            subExpiry.setTextColor(expired ? RED : MUTED);
        } else {
            subExpiry.setText("Бессрочно");
            subExpiry.setTextColor(MUTED);
        }
    }

    /** "1.2GB / 10GB" -> 0.12; an unknown or infinite quota reads as an empty bar. */
    private float trafficRatio(String label) {
        try {
            String[] parts = label.split("/");
            if (parts.length != 2) return 0;
            double used = parseBytes(parts[0].trim());
            double total = parseBytes(parts[1].trim());
            if (total <= 0 || used < 0) return 0;
            return (float) Math.min(1, used / total);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private double parseBytes(String value) {
        if (TextUtils.isEmpty(value) || "∞".equals(value) || "—".equals(value)) return -1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([0-9.,]+)\\s*([KMGT]?B)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value);
        if (!matcher.find()) return -1;
        double number = Double.parseDouble(matcher.group(1).replace(',', '.'));
        String unit = matcher.group(2).toUpperCase(Locale.US);
        double multiplier = 1;
        if (unit.startsWith("K")) multiplier = 1024;
        else if (unit.startsWith("M")) multiplier = 1024 * 1024;
        else if (unit.startsWith("G")) multiplier = 1024 * 1024 * 1024;
        else if (unit.startsWith("T")) multiplier = 1024d * 1024 * 1024 * 1024;
        return number * multiplier;
    }

    private void updateConnection() {
        if (powerView == null) return;
        boolean connected = VpnController.isConnected();
        lastConnected = connected;
        String timer = "";
        if (connected) {
            long elapsed = Math.max(0, (System.currentTimeMillis() - VpnController.getConnectedSince()) / 1000L);
            timer = String.format(Locale.US, "%02d:%02d:%02d", elapsed / 3600, elapsed / 60 % 60, elapsed % 60);
        }
        VpnServer selected = selectedServer();
        VpnServer current = VpnController.getCurrent();
        boolean switchServer = connected && selected != null && current != null && !selected.id.equals(current.id);
        boolean coreMissing = !connected && !connectionBusy && !VpnController.isCoreAvailable();
        powerView.setState(connected, connectionBusy, "", timer);
        powerView.setContentDescription(connectionBusy ? "Подключение" : switchServer ? "Сменить сервер"
                : connected ? "Отключить" : "Подключить");
        statusText.setText(connectionBusy ? "Подключение…"
                : switchServer ? "Сменить сервер"
                : connected ? "Подключено"
                : coreMissing ? "VPN-ядро недоступно" : "Нажмите для подключения");
        statusText.setTextColor(connectionBusy ? ACCENT : connected ? GREEN : coreMissing ? RED : TEXT);
        if (multiSelect) {
            statusSub.setText("Выбрано для удаления: " + selectedForDeletion.size());
            statusSub.setTextColor(AMBER);
        } else if (connected) {
            statusSub.setText((current == null ? "" : current.name + " · ") + timer);
            statusSub.setTextColor(MUTED);
        } else if (coreMissing) {
            statusSub.setText("Устройство не поддерживает VPN-ядро");
            statusSub.setTextColor(RED);
        } else if (selected != null) {
            statusSub.setText(selected.name);
            statusSub.setTextColor(MUTED);
        } else {
            statusSub.setText("Сервер не выбран");
            statusSub.setTextColor(MUTED);
        }
    }

    private void applyOpenBehavior() {
        if (VpnStore.isUpdateOnOpen() && !TextUtils.isEmpty(VpnStore.getSubscriptionUrl())) refresh();
        if (VpnStore.isPingOnOpen(true)) pingServers(subscriptionServers);
        if (VpnStore.isPingOnOpen(false)) pingServers(manualServers);
        boolean connectFromSubscription = VpnStore.isConnectOnOpen(true);
        if (connectFromSubscription || VpnStore.isConnectOnOpen(false)) {
            List<VpnServer> group = connectFromSubscription ? subscriptionServers : manualServers;
            if (group.isEmpty()) group = allServers();
            VpnServer target = chooseServer(group, connectFromSubscription);
            if (target != null && !VpnController.isConnected()) changeConnection(target);
        }
    }

    private VpnServer chooseServer(List<VpnServer> group, boolean subscription) {
        if (group == null || group.isEmpty()) return null;
        int mode = VpnStore.getConnectMode(subscription);
        if (mode == 2) return group.get(new java.util.Random().nextInt(group.size()));
        if (mode == 1) {
            VpnServer best = group.get(0);
            int bestPing = latencyOrder(best);
            for (VpnServer server : group) {
                int value = latencyOrder(server);
                if (value < bestPing) {
                    best = server;
                    bestPing = value;
                }
            }
            return best;
        }
        String last = VpnStore.getLastServerId();
        for (VpnServer server : group) if (server.id.equals(last)) return server;
        return group.get(0);
    }

    private void toggleConnection() {
        if (connectionBusy) return;
        VpnServer selected = selectedServer();
        VpnServer current = VpnController.getCurrent();
        if (VpnController.isConnected()) {
            if (selected != null && current != null && !selected.id.equals(current.id)) changeConnection(selected);
            else changeConnection(null);
        } else if (selected == null) showAddMenu();
        else changeConnection(selected);
    }

    private void changeConnection(VpnServer target) {
        if (connectionBusy || destroyed) return;
        connectionBusy = true;
        updateConnection();
        CORE_QUEUE.execute(() -> {
            String error = null;
            try {
                if (target == null) VpnController.disconnect();
                else error = VpnController.connect(target);
            } catch (Exception e) {
                error = e.getMessage() == null ? "Ошибка подключения" : e.getMessage();
            } finally {
                connectionBusy = false;
            }
            final String result = error;
            ui(() -> { if (result != null) toast(result); reload(); });
        });
    }

    private void showAddMenu() {
        showIconMenu("Добавить подключение",
                new String[]{"Добавить подписку", "Импортировать из буфера обмена", "Добавить ссылку вручную"},
                new String[]{VpnIcon.SYNC, VpnIcon.CONTENT_PASTE, VpnIcon.ADD},
                (dialog, which) -> {
                    if (which == 0) promptForSubscription();
                    else if (which == 1) importFromClipboard();
                    else promptForLink();
                });
    }

    private void showSortMenu(boolean subscription) {
        showIconMenu("Правило сортировки серверов",
                new String[]{"Без сортировки", "По задержке", "По алфавиту"},
                new String[]{VpnIcon.TUNE, VpnIcon.SPEED, VpnIcon.SORT},
                (dialog, which) -> {
                    VpnStore.setSortMode(subscription, which);
                    reload();
                });
    }

    private void showConnectModeMenu(boolean subscription) {
        showIconMenu("Подключиться к",
                new String[]{"Последнему использованному", "С наименьшей задержкой", "Случайному"},
                new String[]{VpnIcon.CHECK, VpnIcon.SPEED, VpnIcon.SHUFFLE},
                (dialog, which) -> {
                    VpnStore.setConnectMode(subscription, which);
                    toast("Способ выбора сервера сохранён");
                });
    }

    private void showIconMenu(String title, String[] labels, String[] glyphs, DialogInterface.OnClickListener listener) {
        if (getParentActivity() == null) return;
        ArrayAdapter<String> menuAdapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = row(context);
                row.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14), AndroidUtilities.dp(20), AndroidUtilities.dp(14));
                row.setBackground(ripple(10));
                VpnIcon icon = new VpnIcon(context, glyphs[position]);
                icon.setColor(ACCENT);
                icon.setTextSizeDp(22);
                row.addView(icon, lin(26, 26, 0, 0, 14, 0));
                TextView label = text(context, labels[position], 15, TEXT, false);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(label, linWeight(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 0, 0));
                return row;
            }
        };
        MenuListView listView = new MenuListView(context);
        listView.setAdapter(menuAdapter);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        listView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(8));
        listView.setClipToPadding(false);
        final AlertDialog dialog = dialogBuilder().setTitle(title).setView(listView).create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            listener.onClick(dialog, position);
            dialog.dismiss();
        });
        dialog.show();
    }

    private void showGroupMenu(boolean subscription) {
        final List<VpnServer> group = subscription ? subscriptionServers : manualServers;
        showIconMenu(subscription ? "Подписка" : "Серверы вручную",
                new String[]{
                        "Удалить" + (subscription ? " подписку и её серверы" : " выбранные серверы"),
                        "Пинговать все серверы", "Настройки",
                        multiSelect ? "Снять выделение" : "Выбрать несколько"},
                new String[]{VpnIcon.DELETE, VpnIcon.SPEED, VpnIcon.SETTINGS, VpnIcon.SELECT_ALL},
                (dialog, which) -> {
                    if (which == 0) {
                        if (subscription) confirmDeleteSubscription();
                        else confirmDeleteSelected(group);
                    } else if (which == 1) pingServers(group);
                    else if (which == 2) showGroupSettings(subscription);
                    else {
                        multiSelect = !multiSelect;
                        if (!multiSelect) selectedForDeletion.clear();
                        renderList();
                    }
                });
    }

    private void showGeneralSettings() {
        if (getParentActivity() == null) return;
        LinearLayout content = column(context);
        content.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(4), AndroidUtilities.dp(20), AndroidUtilities.dp(4));
        content.addView(switchRow("Подключаться при запуске клиента", VpnStore.isAutoConnectOnLaunch(),
                VpnStore::setAutoConnectOnLaunch));
        content.addView(switchRow("Автовыбор рабочего сервера", VpnStore.isAutoSelectEnabled(),
                VpnStore::setAutoSelectEnabled));
        content.addView(settingsDivider());
        content.addView(valueRow("Добавить подписку", "", v -> promptForSubscription()));
        content.addView(valueRow("Добавить сервер вручную", "", v -> promptForLink()));
        content.addView(valueRow("О приложении", "", v -> dialogBuilder().setTitle("DaskGram VPN")
                .setMessage("Только трафик клиента DaskGram через локальный SOCKS5 127.0.0.1:"
                        + VpnController.SOCKS_PORT + ".\nСистемный VPN не используется.")
                .setPositiveButton("Готово", null).show()));
        dialogBuilder().setTitle("Настройки VPN").setView(content)
                .setPositiveButton("Готово", null).show();
    }

    private void showGroupSettings(boolean subscription) {
        if (getParentActivity() == null) return;
        LinearLayout content = column(context);
        content.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(4), AndroidUtilities.dp(20), AndroidUtilities.dp(4));
        content.addView(switchRow("Пинговать при открытии", VpnStore.isPingOnOpen(subscription),
                value -> VpnStore.setPingOnOpen(subscription, value)));
        content.addView(switchRow("Подключаться при открытии", VpnStore.isConnectOnOpen(subscription),
                value -> VpnStore.setConnectOnOpen(subscription, value)));
        if (subscription) {
            content.addView(switchRow("Обновлять при открытии", VpnStore.isUpdateOnOpen(),
                    VpnStore::setUpdateOnOpen));
        }
        content.addView(switchRow("Разрешить сворачивание", VpnStore.isCollapseAllowed(subscription),
                value -> VpnStore.setCollapseAllowed(subscription, value)));
        content.addView(settingsDivider());
        content.addView(valueRow("Подключиться к", connectModeLabel(VpnStore.getConnectMode(subscription)),
                v -> showConnectModeMenu(subscription)));
        content.addView(valueRow("Правило сортировки серверов", sortModeLabel(VpnStore.getSortMode(subscription)),
                v -> showSortMenu(subscription)));
        dialogBuilder().setTitle("Настройки").setView(content)
                .setPositiveButton("Готово", null).show();
    }

    private View settingsDivider() {
        View line = new View(context);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(1));
        params.topMargin = AndroidUtilities.dp(6);
        params.bottomMargin = AndroidUtilities.dp(6);
        line.setLayoutParams(params);
        return line;
    }

    private View switchRow(String label, boolean checked, SwitchListener listener) {
        LinearLayout row = row(context);
        row.setMinimumHeight(AndroidUtilities.dp(52));
        row.setBackground(ripple(10));
        TextView title = text(context, label, 14, TEXT, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title, linWeight(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));
        Switch toggle = new Switch(context);
        styleSwitch(toggle);
        toggle.setChecked(checked);
        row.addView(toggle, lin(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        toggle.setOnCheckedChangeListener((button, value) -> {
            listener.onChange(value);
            renderList();
        });
        row.setOnClickListener(v -> toggle.toggle());
        return row;
    }

    private View valueRow(String label, String value, View.OnClickListener listener) {
        LinearLayout row = row(context);
        row.setMinimumHeight(AndroidUtilities.dp(52));
        row.setBackground(ripple(10));
        TextView title = text(context, label, 14, TEXT, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title, linWeight(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        TextView current = text(context, value, 12, ACCENT, false);
        current.setSingleLine(true);
        current.setEllipsize(TextUtils.TruncateAt.END);
        current.setMaxWidth(AndroidUtilities.dp(130));
        row.addView(current, lin(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));
        VpnIcon chevron = icon(VpnIcon.CHEVRON_RIGHT, 18);
        chevron.setColor(MUTED);
        row.addView(chevron, lin(24, 24, 2, 0, 0, 0));
        row.setOnClickListener(listener);
        return row;
    }

    private interface SwitchListener {
        void onChange(boolean value);
    }

    private String connectModeLabel(int mode) {
        if (mode == 1) return "с наименьшей задержкой";
        if (mode == 2) return "случайному";
        return "последнему";
    }

    private String sortModeLabel(int mode) {
        if (mode == 1) return "по задержке";
        if (mode == 2) return "по алфавиту";
        return "без сортировки";
    }

    private void confirmDeleteSelected(List<VpnServer> group) {
        if (getParentActivity() == null) return;
        final Set<String> ids = new HashSet<>(selectedForDeletion.isEmpty() && group.size() == 1
                ? Collections.singleton(group.get(0).id) : selectedForDeletion);
        if (ids.isEmpty()) {
            toast("Выберите серверы долгим нажатием");
            return;
        }
        dialogBuilder().setTitle("Удалить выбранные?")
                .setMessage("Будет удалено серверов: " + ids.size() + ".\nПодключённый сервер будет отключён.")
                .setPositiveButton("Удалить", (dialog, which) -> deleteServers(ids))
                .setNegativeButton("Отмена", null).show();
    }

    private void confirmDeleteSubscription() {
        if (getParentActivity() == null) return;
        dialogBuilder().setTitle("Удалить подписку?")
                .setMessage("Все серверы этой подписки будут удалены с устройства.")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    Set<String> ids = new HashSet<>();
                    for (VpnServer server : subscriptionServers) ids.add(server.id);
                    deleteServers(ids);
                    CORE_QUEUE.execute(() -> {
                        VpnStore.setSubscriptionUrl("");
                        VpnStore.setSubscriptionName("");
                        VpnStore.setTrafficLabel("0B / ∞");
                        VpnStore.setExpiry(0);
                        STORE_REVISION.incrementAndGet();
                        ui(this::reload);
                    });
                }).setNegativeButton("Отмена", null).show();
    }

    private void deleteServers(Set<String> ids) {
        if (connectionBusy || destroyed) return;
        connectionBusy = true;
        CORE_QUEUE.execute(() -> {
            try {
                VpnServer current = VpnController.getCurrent();
                if (current != null && ids.contains(current.id)) VpnController.disconnect();
                VpnStore.remove(new ArrayList<>(ids));
                STORE_REVISION.incrementAndGet();
            } finally {
                connectionBusy = false;
            }
            ui(() -> {
                multiSelect = false;
                selectedForDeletion.clear();
                reload();
            });
        });
    }

    private void showServerDetails(VpnServer server) {
        if (getParentActivity() == null) return;
        StringBuilder message = new StringBuilder();
        message.append(server.detailsLabel())
                .append("\nИсточник: ").append(server.isManual() ? "Вручную" : "Подписка")
                .append("\nЗадержка: ").append(latency.containsKey(server.id) ? latencyLabel(latency.get(server.id)) : "не измерена");
        if (!TextUtils.isEmpty(server.link)) {
            message.append("\n\n").append(server.link);
        }
        if (server.isJsonConfig()) {
            message.append("\n\nJSON:\n").append(server.prettyJson());
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        TextView body = text(context, message.toString(), 12, TEXT, false);
        body.setTextIsSelectable(true);
        body.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        scroll.addView(body);
        dialogBuilder().setTitle(server.name).setView(scroll)
                .setPositiveButton("Готово", null).show();
    }

    private void importFromClipboard() {
        if (getParentActivity() == null) return;
        ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast("Буфер обмена пуст");
            return;
        }
        CharSequence value = clip.getItemAt(0).coerceToText(getParentActivity());
        String input = value == null ? "" : value.toString().trim();
        if (input.startsWith("http://") || input.startsWith("https://")) saveSubscription(input);
        else addLinks(input);
    }

    private void addLinks(String input) {
        if (destroyed) return;
        if (TextUtils.isEmpty(input)) {
            toast("Вставьте ссылку подключения");
            return;
        }
        CORE_QUEUE.execute(() -> {
            int added = 0;
            String lastError = "В тексте нет поддерживаемых ссылок";
            for (String raw : input.split("\\r?\\n")) {
                String error = VpnController.addLink(raw.trim());
                if (error == null) added++;
                else lastError = error;
            }
            final String result = added > 0 ? "Добавлено серверов: " + added : lastError;
            STORE_REVISION.incrementAndGet();
            ui(() -> { toast(result); reload(); });
        });
    }

    private void promptForLink() {
        if (getParentActivity() == null) return;
        EditText input = darkInput("vless://…");
        input.setMinLines(2);
        input.setMaxLines(5);
        dialogBuilder().setTitle("Добавить серверы вручную")
                .setView(input)
                .setPositiveButton("Добавить", (dialog, which) -> addLinks(input.getText().toString().trim()))
                .setNegativeButton("Отмена", null).show();
    }

    private void promptForSubscription() {
        if (getParentActivity() == null) return;
        EditText input = darkInput("https://…");
        input.setText(VpnStore.getSubscriptionUrl());
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        dialogBuilder().setTitle("Добавить подписку")
                .setView(input)
                .setPositiveButton("Сохранить", (dialog, which) -> saveSubscription(input.getText().toString().trim()))
                .setNegativeButton("Отмена", null).show();
    }

    private void saveSubscription(String url) {
        Uri parsed = Uri.parse(url);
        if (!("https".equalsIgnoreCase(parsed.getScheme()) || "http".equalsIgnoreCase(parsed.getScheme()))
                || TextUtils.isEmpty(parsed.getHost())) {
            toast("Нужна ссылка http:// или https://");
            return;
        }
        VpnStore.setSubscriptionUrl(url);
        refresh();
    }

    private void refresh() {
        if (subscriptionBusy || destroyed) return;
        final String url = VpnStore.getSubscriptionUrl();
        if (TextUtils.isEmpty(url)) {
            promptForSubscription();
            return;
        }
        subscriptionBusy = true;
        renderList();
        updateSubscription();
        IO_QUEUE.execute(() -> {
            try {
                final String body = VpnController.downloadSubscription(url);
                CORE_QUEUE.execute(() -> {
                    int count = VpnController.importSubscription(url, body);
                    String error = VpnController.getLastError();
                    STORE_REVISION.incrementAndGet();
                    subscriptionBusy = false;
                    ui(() -> {
                        toast(count < 0 ? error : "Подписка обновлена: " + count + " серверов");
                        reload();
                    });
                });
            } catch (Exception e) {
                subscriptionBusy = false;
                final String message = TextUtils.isEmpty(e.getMessage()) ? "Не удалось загрузить подписку" : e.getMessage();
                ui(() -> { toast(message); renderList(); updateSubscription(); });
            }
        });
    }

    private void pingServers(List<VpnServer> group) {
        if (probing || destroyed || group.isEmpty()) return;
        probing = true;
        final AtomicInteger remaining = new AtomicInteger(group.size());
        for (VpnServer server : group) {
            probes.execute(() -> {
                int value = VpnController.pingServer(server);
                ui(() -> {
                    latency.put(server.id, value);
                    if (remaining.decrementAndGet() == 0) {
                        probing = false;
                        renderList();
                    } else {
                        listDirty = true;
                    }
                });
            });
        }
    }

    private String latencyLabel(int value) {
        return value < 0 ? "недоступен" : value + " ms";
    }

    /** Ping colour as in Hiddify: green under 800 ms, orange under 1500, red above. */
    private int pingColor(int value) {
        if (value < 0) return RED;
        if (value < 800) return GREEN;
        if (value < 1500) return AMBER;
        return RED;
    }

    private android.graphics.drawable.Drawable surface(int color, int radius, int border, boolean ripple) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(AndroidUtilities.dp(radius));
        if (border != Color.TRANSPARENT) shape.setStroke(AndroidUtilities.dp(1), border);
        if (!ripple) return shape;
        return new RippleDrawable(ColorStateList.valueOf(0x1FA79BFF), shape, null);
    }

    private android.graphics.drawable.Drawable ripple(int radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(AndroidUtilities.dp(radius));
        return new RippleDrawable(ColorStateList.valueOf(0x1FA79BFF), null, mask);
    }

    /** The thin rounded traffic bar of the Hiddify profile card. */
    private class TrafficBar extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private float ratio;

        TrafficBar(Context context) {
            super(context);
        }

        void setRatio(float value) {
            ratio = Math.max(0, Math.min(1, value));
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float radius = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            paint.setColor(TRACK);
            canvas.drawRoundRect(rect, radius, radius, paint);
            if (ratio > 0) {
                float width = Math.max(getHeight(), getWidth() * ratio);
                rect.set(0, 0, width, getHeight());
                paint.setColor(ratio >= 1 ? RED : ACCENT);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
        }
    }

    /**
     * One proxy row inside its group card: flag tile, name, small-caps protocol
     * line and a ping slot. Horizontal swipe reveals actions — right: ping and
     * share (url / json / qr); left: delete. JSON servers of a subscription get
     * only ping and delete, since they carry no share link to export.
     */
    private View buildServerRow(VpnServer server, boolean subscription) {
        final boolean jsonOnly = subscription && server.isJsonConfig() && TextUtils.isEmpty(server.link);
        final float reveal = AndroidUtilities.dp(jsonOnly ? 56 : 112);

        FrameLayout container = new FrameLayout(context);
        container.setClipChildren(false);

        LinearLayout actionsRight = row(context);
        actionsRight.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        actionsRight.setPadding(AndroidUtilities.dp(8), 0, 0, 0);
        VpnIcon pingAction = icon(VpnIcon.SPEED, 22);
        pingAction.setColor(GREEN);
        pingAction.setOnClickListener(v -> { pingOne(server); closeSwipe(container); });
        actionsRight.addView(pingAction, lin(44, 44));
        if (!jsonOnly) {
            VpnIcon shareAction = icon(VpnIcon.SHARE, 22);
            shareAction.setColor(ACCENT);
            shareAction.setOnClickListener(v -> { closeSwipe(container); showShareMenu(server); });
            actionsRight.addView(shareAction, lin(44, 44, 8, 0, 0, 0));
        }
        container.addView(actionsRight, frame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        LinearLayout actionsLeft = row(context);
        actionsLeft.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        actionsLeft.setPadding(0, 0, AndroidUtilities.dp(8), 0);
        VpnIcon deleteAction = icon(VpnIcon.DELETE, 22);
        deleteAction.setColor(RED);
        deleteAction.setOnClickListener(v -> { closeSwipe(container); confirmDeleteOne(server); });
        actionsLeft.addView(deleteAction, lin(44, 44));
        container.addView(actionsLeft, frame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));

        LinearLayout row = row(context);
        row.setMinimumHeight(AndroidUtilities.dp(64));
        row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        TextView checkbox = text(context, VpnIcon.CHECK_BOX_OUTLINE, 22, MUTED, false);
        checkbox.setTypeface(VpnIcon.typefaceFor(context));
        checkbox.setGravity(Gravity.CENTER);
        row.addView(checkbox, lin(28, 28, 0, 0, 6, 0));
        TextView flag = text(context, "", 20, TEXT, false);
        flag.setGravity(Gravity.CENTER);
        flag.setBackground(surface(TRACK, 8, Color.TRANSPARENT, false));
        row.addView(flag, lin(40, 40, 0, 0, 10, 0));
        LinearLayout info = column(context);
        TextView name = text(context, "", 14, TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView meta = text(context, "", 11, MUTED, false);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        info.addView(meta, lin(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        row.addView(info, linWeight(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        TextView ping = text(context, "—", 13, MUTED, true);
        ping.setGravity(Gravity.CENTER);
        ping.setBackground(ripple(8));
        row.addView(ping, lin(72, 40, 4, 0, 0, 0));
        container.addView(row, frame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        boolean active = VpnController.isConnected() && VpnController.getCurrent() != null
                && VpnController.getCurrent().id.equals(server.id);
        boolean picked = !active && server.id.equals(selectedId);
        boolean checked = selectedForDeletion.contains(server.id);
        row.setBackground(surface(active || picked ? CARD_ACTIVE : CARD, 12,
                active ? 0xFF5A6BCA : Color.TRANSPARENT, false));
        checkbox.setText(checked ? VpnIcon.CHECK_BOX : VpnIcon.CHECK_BOX_OUTLINE);
        checkbox.setTextColor(checked ? ACCENT : MUTED);
        checkbox.setVisibility(multiSelect ? View.VISIBLE : View.GONE);
        flag.setText(TextUtils.isEmpty(server.flag()) ? "\uD83C\uDF10" : server.flag());
        name.setText(server.name);
        name.setTextColor(active || picked ? TEXT : 0xD9ECECF1);
        meta.setText(server.detailsSpannable(11));
        meta.setTextColor(active ? GREEN : MUTED);
        Integer value = latency.get(server.id);
        if (value == null) {
            ping.setText("—");
            ping.setTextColor(MUTED);
        } else if (value < 0) {
            ping.setText("✕");
            ping.setTextColor(RED);
        } else {
            ping.setText(String.valueOf(value));
            ping.setTextColor(pingColor(value));
        }
        ping.setOnClickListener(v -> pingOne(server));
        ping.setOnLongClickListener(v -> { showServerDetails(server); return true; });
        row.setOnClickListener(v -> {
            if (isSwipeOpen(container)) { closeSwipe(container); return; }
            if (connectionBusy) return;
            if (multiSelect) {
                if (!selectedForDeletion.add(server.id)) selectedForDeletion.remove(server.id);
                renderList();
            } else {
                selectedId = server.id;
                VpnStore.setLastServerId(selectedId);
                renderList();
            }
        });
        row.setOnLongClickListener(v -> {
            showServerDetails(server);
            return true;
        });
        attachSwipe(row, container, reveal, jsonOnly);
        return container;
    }

    private boolean isSwipeOpen(View container) {
        View content = ((FrameLayout) container).getChildAt(2);
        return content != null && Math.abs(content.getTranslationX()) > AndroidUtilities.dp(4);
    }

    private void closeSwipe(View container) {
        View content = ((FrameLayout) container).getChildAt(2);
        if (content != null) content.animate().translationX(0).setDuration(160).start();
    }

    /** Horizontal drag moves the row aside to reveal the action buttons. */
    private void attachSwipe(View content, FrameLayout container, float reveal, boolean jsonOnly) {
        content.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startTranslation;
            boolean dragging;
            final int slop = AndroidUtilities.dp(8);

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startTranslation = content.getTranslationX();
                        dragging = false;
                        return false;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                            dragging = true;
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        if (dragging) {
                            float target = startTranslation + dx;
                            float maxRight = reveal;
                            float maxLeft = -AndroidUtilities.dp(56);
                            content.setTranslationX(Math.max(maxLeft, Math.min(maxRight, target)));
                            return true;
                        }
                        return false;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            float translation = content.getTranslationX();
                            float openTo = 0;
                            if (translation > reveal * 0.4f) openTo = reveal;
                            else if (translation < -AndroidUtilities.dp(24)) openTo = -AndroidUtilities.dp(56);
                            content.animate().translationX(openTo).setDuration(160).start();
                            return true;
                        }
                        return false;
                }
                return false;
            }
        });
    }

    private void confirmDeleteOne(VpnServer server) {
        if (getParentActivity() == null) return;
        dialogBuilder().setTitle("Удалить сервер?")
                .setMessage(server.name + "\nЕсли это последний сервер группы, блок тоже исчезнет.")
                .setPositiveButton("Удалить", (dialog, which) -> deleteServers(Collections.singleton(server.id)))
                .setNegativeButton("Отмена", null).show();
    }

    private void showShareMenu(VpnServer server) {
        showIconMenu("Поделиться сервером",
                new String[]{"Ссылка (URL)", "JSON-конфиг", "QR-код"},
                new String[]{VpnIcon.LINK, VpnIcon.CODE, VpnIcon.QR_CODE},
                (dialog, which) -> {
                    if (which == 0) {
                        copyToClipboard(server.link);
                        toast("Ссылка скопирована");
                    } else if (which == 1) {
                        copyToClipboard(server.prettyJson());
                        toast("JSON скопирован");
                    } else {
                        showQrCode(server);
                    }
                });
    }

    private void copyToClipboard(String value) {
        if (getParentActivity() == null || TextUtils.isEmpty(value)) return;
        ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("server", value));
    }

    private void showQrCode(VpnServer server) {
        if (getParentActivity() == null) return;
        String payload = TextUtils.isEmpty(server.link) ? server.prettyJson() : server.link;
        try {
            android.graphics.Bitmap bitmap = new org.telegram.messenger.TelegramQRCodeWriter()
                    .encode(payload, AndroidUtilities.dp(260), AndroidUtilities.dp(260), null, null,
                            1.0f, 0xFFFFFFFF, 0xFF000000);
            android.widget.ImageView image = new android.widget.ImageView(context);
            image.setImageBitmap(bitmap);
            image.setBackgroundColor(Color.WHITE);
            image.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
            LinearLayout box = column(context);
            box.setGravity(Gravity.CENTER);
            box.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(4));
            box.addView(image, LayoutHelper.createLinear(280, 280, Gravity.CENTER_HORIZONTAL));
            dialogBuilder().setTitle(server.name).setView(box)
                    .setPositiveButton("Готово", null).show();
        } catch (Exception e) {
            toast("Не удалось построить QR-код");
        }
    }

    private void runAutoSelect() {
        if (connectionBusy || destroyed) return;
        List<VpnServer> all = allServers();
        if (all.isEmpty()) {
            toast("Сначала добавьте серверы");
            return;
        }
        connectionBusy = true;
        updateConnection();
        toast("Ищу рабочий сервер…");
        CORE_QUEUE.execute(() -> {
            VpnServer best = VpnController.autoSelect(all);
            String error = null;
            if (best == null) {
                error = "Ни один сервер не ответил";
            } else {
                error = VpnController.connect(best);
                if (error == null) {
                    VpnStore.setLastServerId(best.id);
                }
            }
            connectionBusy = false;
            final String result = error;
            final VpnServer picked = best;
            ui(() -> {
                if (result != null) toast(result);
                else toast("Подключено: " + (picked == null ? "" : picked.name));
                reload();
            });
        });
    }

    private void pingOne(VpnServer server) {
        if (destroyed) return;
        probes.execute(() -> {
            int value = VpnController.pingServer(server);
            ui(() -> {
                latency.put(server.id, value);
                listDirty = true;
            });
        });
    }
}