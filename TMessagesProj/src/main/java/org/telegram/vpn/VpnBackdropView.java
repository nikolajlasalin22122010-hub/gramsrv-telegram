package org.telegram.vpn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * The Hiddify home backdrop: a faint world map under a soft "eclipse" glow
 * centred a little above the middle, where the connection button sits.
 */
public class VpnBackdropView extends FrameLayout {
    private static Bitmap map;
    private static boolean mapLoaded;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private Shader glow;
    private int glowWidth, glowHeight;

    public VpnBackdropView(Context context) {
        super(context);
        init(context);
    }

    public VpnBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);
        if (!mapLoaded) {
            mapLoaded = true;
            try {
                map = BitmapFactory.decodeStream(context.getAssets().open("vpn/world_map.png"));
            } catch (Exception ignored) {
                map = null;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        if (glowWidth != width || glowHeight != height || glow == null) {
            glowWidth = width;
            glowHeight = height;
            float centerX = width / 2f;
            float centerY = height * 0.425f;
            glow = new RadialGradient(centerX, centerY, Math.max(width, height) * 0.85f,
                    new int[]{0x0BFFFFFF, 0x04FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        }
        paint.setShader(glow);
        paint.setColorFilter(null);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        if (map != null) {
            // BoxFit.cover, tinted white at ~15% as in the Hiddify dark theme.
            float scale = Math.max(width / (float) map.getWidth(), height / (float) map.getHeight());
            int drawWidth = Math.round(map.getWidth() * scale);
            int drawHeight = Math.round(map.getHeight() * scale);
            dst.set((width - drawWidth) / 2, (height - drawHeight) / 2,
                    (width + drawWidth) / 2, (height + drawHeight) / 2);
            src.set(0, 0, map.getWidth(), map.getHeight());
            paint.setColorFilter(new PorterDuffColorFilter(0x26FFFFFF, PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(map, src, dst, paint);
            paint.setColorFilter(null);
        }
    }
}