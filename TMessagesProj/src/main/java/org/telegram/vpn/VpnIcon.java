package org.telegram.vpn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/** Google Material Symbols rendered from the bundled variable-font glyph set. */
public class VpnIcon extends View {
    public static final String SETTINGS = "\ue8b8";
    public static final String ADD = "\ue145";
    public static final String DELETE = "\ue872";
    public static final String REFRESH = "\ue5d5";
    public static final String MORE = "\ue5d3";
    public static final String EXPAND_MORE = "\ue5cf";
    public static final String EXPAND_LESS = "\ue5ce";
    public static final String CHEVRON_RIGHT = "\ue409";
    public static final String POWER = "\ue8ac";
    public static final String SPEED = "\ue9e4";
    public static final String CHECK = "\ue5ca";
    public static final String CLOSE = "\ue14c";
    public static final String SYNC = "\ue627";
    public static final String TUNE = "\ue429";
    public static final String SHUFFLE = "\ue043";
    public static final String CHECK_BOX = "\ue834";
    public static final String CHECK_BOX_OUTLINE = "\ue835";
    public static final String ARROW_DROP_DOWN = "\ue5c5";
    public static final String CONTENT_PASTE = "\ue14f";
    public static final String SELECT_ALL = "\ue162";
    public static final String SORT = "\ue053";
    public static final String ARROW_BACK = "\ue5c4";
    public static final String DELETE_SWEEP = "\ue16c";
    public static final String FLAG = "\ue153";
    public static final String PUBLIC = "\ue80b";
    public static final String DATA_USAGE = "\ue1af";
    public static final String SCHEDULE = "\ue192";
    public static final String SHARE = "\ue80d";
    public static final String LINK = "\ue157";
    public static final String CODE = "\ue86f";
    public static final String QR_CODE = "\uef6b";
    public static final String QR_CODE_SCANNER = "\uf206";
    public static final String AUTO_FIX = "\ue663";
    public static final String IMAGE = "\ue3f4";
    public static final String EDIT = "\ue3c9";
    public static final String VISIBILITY = "\ue8f4";

    private static Typeface typeface;
    private static boolean fontOk;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String glyph;
    private int color = Color.WHITE;
    private float textSizeDp = 24f;

    public VpnIcon(Context context, String glyph) {
        super(context);
        this.glyph = glyph;
        init(context);
    }

    public VpnIcon(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.glyph = MORE;
        init(context);
    }

    private void init(Context context) {
        paint.setTypeface(typefaceFor(context));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(color);
        setContentDescription(glyph);
    }

    public void setColor(int value) {
        if (color != value) {
            color = value;
            paint.setColor(value);
            invalidate();
        }
    }

    public static Typeface typefaceFor(Context context) {
        if (typeface == null) {
            try {
                typeface = Typeface.createFromAsset(context.getAssets(), "fonts/material_symbols_rounded.ttf");
                fontOk = typeface != null && typeface != Typeface.DEFAULT;
            } catch (Throwable ignored) {
                typeface = Typeface.DEFAULT;
                fontOk = false;
            }
        }
        return typeface;
    }

    /** True when the Material Symbols font loaded; otherwise glyphs need a fallback. */
    public static boolean isFontReady(Context context) {
        typefaceFor(context);
        return fontOk;
    }

    /** Readable stand-in when the icon font is unavailable on this device. */
    public static String fallbackFor(String glyph) {
        if (glyph == null) return "?";
        if (SETTINGS.equals(glyph)) return "⚙";
        if (ADD.equals(glyph)) return "+";
        if (DELETE.equals(glyph) || DELETE_SWEEP.equals(glyph)) return "✕";
        if (MORE.equals(glyph)) return "⋮";
        if (CHEVRON_RIGHT.equals(glyph)) return "›";
        if (ARROW_DROP_DOWN.equals(glyph) || EXPAND_MORE.equals(glyph)) return "▾";
        if (EXPAND_LESS.equals(glyph)) return "▴";
        if (ARROW_BACK.equals(glyph)) return "‹";
        if (SYNC.equals(glyph) || REFRESH.equals(glyph)) return "↻";
        if (SPEED.equals(glyph)) return "⚡";
        if (AUTO_FIX.equals(glyph)) return "✦";
        if (SHARE.equals(glyph)) return "↗";
        if (LINK.equals(glyph)) return "⎘";
        if (CODE.equals(glyph)) return "{}";
        if (QR_CODE.equals(glyph) || QR_CODE_SCANNER.equals(glyph)) return "▦";
        if (CHECK_BOX.equals(glyph)) return "☑";
        if (CHECK_BOX_OUTLINE.equals(glyph)) return "☐";
        if (CHECK.equals(glyph)) return "✓";
        if (CLOSE.equals(glyph)) return "✕";
        if (CONTENT_PASTE.equals(glyph)) return "⎘";
        if (SELECT_ALL.equals(glyph)) return "☑";
        if (SORT.equals(glyph)) return "⇅";
        if (SHUFFLE.equals(glyph)) return "⇄";
        if (TUNE.equals(glyph)) return "≡";
        if (POWER.equals(glyph)) return "⏻";
        return "•";
    }

    public void setText(String value) {
        if (!glyph.equals(value)) {
            glyph = value;
            invalidate();
        }
    }

    public void setTextSizeDp(float value) {
        if (textSizeDp != value) {
            textSizeDp = value;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        paint.setTextSize(textSizeDp * density);
        String draw = fontOk ? glyph : fallbackFor(glyph);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(draw, getWidth() / 2f, baseline, paint);
    }
}
