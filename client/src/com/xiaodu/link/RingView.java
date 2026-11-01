package com.xiaodu.link;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** 状态光环：呼吸动画 + 按状态换色 */
public class RingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = 0xFF6B7280;
    private float phase;
    private float density;

    public RingView(Context c) {
        super(c);
        init(c);
    }

    public RingView(Context c, AttributeSet a) {
        super(c, a);
        init(c);
    }

    private void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
    }

    public void setColor(int c) {
        if (color != c) {
            color = c;
            invalidate();
        }
    }

    private float dp(float v) {
        return v * density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float base = Math.min(getWidth(), getHeight()) / 2f * 0.60f;
        phase += 0.055f;
        if (phase > (float) (Math.PI * 2)) phase = 0;
        float level = (float) (0.5 + 0.5 * Math.sin(phase));

        // 外圈呼吸
        for (int i = 3; i >= 1; i--) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.6f));
            int alpha = (int) ((5 - i) * 26 * (0.4f + 0.6f * level));
            paint.setColor((color & 0x00FFFFFF) | (alpha << 24));
            canvas.drawCircle(cx, cy, base + dp(9) * i * (0.9f + 0.2f * level), paint);
        }

        // 内部淡色填充
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((color & 0x00FFFFFF) | 0x22000000);
        canvas.drawCircle(cx, cy, base, paint);

        // 主圈
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(color);
        canvas.drawCircle(cx, cy, base, paint);

        postInvalidateDelayed(33);
    }
}
