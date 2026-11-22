package org.telegram.vpn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Drawable wrapper around Material Symbols so ActionBar can use the same glyphs. */
public class VpnIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String glyph;
    private final boolean fontOk;
    private final int size;

    public VpnIconDrawable(Context context, String glyph, int color) {
        this.glyph = glyph;
        paint.setTypeface(VpnIcon.typefaceFor(context));
        this.fontOk = VpnIcon.isFontReady(context);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.CENTER);
        size = Math.round(24 * context.getResources().getDisplayMetrics().density);
        setSize(size, size);
    }

    private void setSize(int width, int height) {
        setBounds(0, 0, width, height);
    }

    @Override
    public void draw(Canvas canvas) {
        android.graphics.Rect bounds = getBounds();
        paint.setTextSize(size);
        String draw = fontOk ? glyph : VpnIcon.fallbackFor(glyph);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(draw, bounds.centerX(), baseline, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }
}
