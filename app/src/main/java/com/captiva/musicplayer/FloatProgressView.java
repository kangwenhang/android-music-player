package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 悬浮条进度条:细轨道 + 已播蓝色段 + 白色圆点手柄。
 * 独立自绘而不用 ProgressBar:API 17 横向 ProgressBar 不带 thumb,
 * 自绘 3 笔即出渲染图同款效果(轨道/已播/白点)。
 */
public class FloatProgressView extends View {
    private final Paint paintTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintPlayed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float fraction; // 0..1

    public FloatProgressView(Context context) {
        this(context, null);
    }

    public FloatProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paintTrack.setColor(0xFF3A3A44);
        paintPlayed.setColor(0xFF4A7DE8);
        paintDot.setColor(0xFFFFFFFF);
    }

    /** 设置进度(0..1,自动钳制) */
    public void setFraction(float f) {
        if (f < 0) f = 0;
        if (f > 1) f = 1;
        if (f != fraction) {
            fraction = f;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        float cy = h / 2f;
        float trackH = h / 4f; // 10dp 高的 view 里画 2.5dp 细线
        float w = getWidth();
        if (w <= 0) return;

        // 轨道
        rect.set(0, cy - trackH / 2f, w, cy + trackH / 2f);
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paintTrack);

        // 已播段(宽度为 0 时不画,避免圆点挤在起点)
        float playedW = w * fraction;
        if (playedW > trackH) {
            rect.set(0, cy - trackH / 2f, playedW, cy + trackH / 2f);
            canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paintPlayed);
        }

        // 白点手柄:始终可见,随进度滑动
        float dotR = h * 0.3f;
        float dotX = playedW < dotR ? dotR : playedW;
        if (dotX > w - dotR) dotX = w - dotR;
        canvas.drawCircle(dotX, cy, dotR, paintDot);
    }
}
