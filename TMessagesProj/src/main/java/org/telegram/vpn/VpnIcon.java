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

    private static Typeface typeface;
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
            } catch (Exception ignored) {
                typeface = Typeface.DEFAULT;
            }
        }
        return typeface;
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
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(glyph, getWidth() / 2f, baseline, paint);
    }
}
