package org.telegram.vpn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * The Hiddify connection button: three concentric circles around a small disc
 * with the power glyph. The outer rings breathe while connecting or connected;
 * their sizes are fixed fractions of the view, so nothing ever spills out.
 */
public class VpnPowerView extends View {
    private static final int IDLE = 0xFF4A4D8B;
    private static final int CONNECTED = 0xFF44A334;
    private static final int STARTING = 0xFF455FE9;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path power = new Path();
    private final RectF powerArc = new RectF();
    private boolean connected;
    private boolean busy;
    private boolean animationsEnabled = true;
    private float cx, cy, inner;
    private int baseColor = IDLE;
    private Shader disc;
    private int shaderWidth, shaderHeight, shaderColor;

    public VpnPowerView(Context context) {
        super(context);
        init();
    }

    public VpnPowerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setState(boolean connected, String status, String timer) {
        setState(connected, false, status, timer);
    }

    /** Status and timer are shown by the fragment under the button, as in Hiddify. */
    public void setState(boolean connected, boolean busy, String status, String timer) {
        this.connected = connected;
        this.busy = busy;
        baseColor = busy ? STARTING : connected ? CONNECTED : IDLE;
        String description = busy ? "Подключение" : connected ? "Отключить VPN" : "Подключить VPN";
        if (!description.contentEquals(getContentDescription() == null ? "" : getContentDescription())) {
            setContentDescription(description);
        }
        invalidate();
    }

    public void setAnimationsEnabled(boolean enabled) {
        animationsEnabled = enabled;
        if (enabled) invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        cx = getWidth() / 2f;
        cy = getHeight() / 2f;
        // Hiddify proportions: outer 84, middle 60, disc 36 of a 168 box.
        float unit = Math.min(getWidth(), getHeight()) / 168f;
        if (unit <= 0) return;
        inner = 36f * unit;
        boolean breathing = (connected || busy) && animationsEnabled && isShown();
        float phase = breathing ? (SystemClock.uptimeMillis() % 4000L) / 4000f : 0f;
        float wave = breathing ? 0.8f + 0.2f * (0.5f - 0.5f * (float) Math.cos(phase * 2 * Math.PI)) : 1f;

        fill.setColor(withAlpha(baseColor, 0.15f));
        canvas.drawCircle(cx, cy, 84f * unit * wave, fill);
        fill.setColor(withAlpha(baseColor, 0.30f));
        canvas.drawCircle(cx, cy, 60f * unit * (breathing ? wave + (1f - wave) / 3f : 1f), fill);

        if (shaderWidth != getWidth() || shaderHeight != getHeight() || shaderColor != baseColor || disc == null) {
            shaderWidth = getWidth();
            shaderHeight = getHeight();
            shaderColor = baseColor;
            disc = new LinearGradient(cx, cy - inner, cx, cy + inner,
                    new int[]{withAlpha(baseColor, 0.90f), baseColor}, null, Shader.TileMode.CLAMP);
        }
        canvas.save();
        if (isPressed()) canvas.scale(0.96f, 0.96f, cx, cy);
        fill.setShader(disc);
        canvas.drawCircle(cx, cy, inner, fill);
        fill.setShader(null);

        // The power glyph, drawn as a path so it never depends on a font.
        float glyph = inner * 0.62f;
        float arcR = glyph * 0.5f;
        float gapDeg = 42f;
        powerArc.set(cx - arcR, cy - arcR + glyph * 0.06f, cx + arcR, cy + arcR + glyph * 0.06f);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(Math.max(dp(2), inner * 0.105f));
        canvas.drawArc(powerArc, -90f + gapDeg, 360f - 2 * gapDeg, false, stroke);
        float topY = cy - glyph * 0.56f;
        canvas.drawLine(cx, topY, cx, topY + glyph * 0.52f, stroke);
        canvas.restore();

        if (breathing) postInvalidateDelayed(40);
    }

    private static int withAlpha(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0xFFFFFF);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float dx = event.getX() - cx, dy = event.getY() - cy;
            float reach = inner + dp(16);
            if (dx * dx + dy * dy > reach * reach) return false;
        }
        boolean handled = super.onTouchEvent(event);
        invalidate();
        return handled;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}