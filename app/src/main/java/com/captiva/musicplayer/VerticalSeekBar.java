package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.SeekBar;

/**
 * 垂直 SeekBar(均衡器专用)
 * 自定义绘制:圆角轨道 + 蓝色渐变进度条 + 中心参考线 + 发光手柄
 * 兼容 API 17+
 */
public class VerticalSeekBar extends SeekBar {

    private Paint trackPaint;
    private Paint progressPaint;
    private Paint centerLinePaint;
    private Paint thumbPaint;
    private Paint thumbBorderPaint;
    private Paint thumbGlowPaint;

    private int trackColor = 0xFF2A2A34;
    private int progressColor = 0xFF4FC3F7;
    private int progressColorDark = 0xFF0288D1;
    private int centerLineColor = 0xFF4A4A56;
    private int thumbColor = 0xFFFFFFFF;
    private int thumbBorderColor = 0xFF4FC3F7;
    private int thumbGlowColor = 0x554FC3F7;

    private int trackWidth = 8;  // dp
    private int thumbRadius = 12; // dp

    private float density;

    /** 保存 listener 引用(getOnSeekBarChangeListener 是隐藏 API) */
    private OnSeekBarChangeListener listener;

    public VerticalSeekBar(Context context) {
        super(context);
        init(context);
    }

    public VerticalSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public VerticalSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;

        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(trackColor);

        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setColor(progressColor);

        centerLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerLinePaint.setColor(centerLineColor);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setColor(thumbColor);

        thumbBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbBorderPaint.setColor(thumbBorderColor);
        thumbBorderPaint.setStyle(Paint.Style.STROKE);
        thumbBorderPaint.setStrokeWidth(2 * density);

        thumbGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbGlowPaint.setColor(thumbGlowColor);
        thumbGlowPaint.setStyle(Paint.Style.STROKE);
        thumbGlowPaint.setStrokeWidth(3 * density);

        // 隐藏系统默认的 thumb 和 progress
        setThumb(null);
        setProgressDrawable(null);
    }

    @Override
    public void setOnSeekBarChangeListener(OnSeekBarChangeListener l) {
        super.setOnSeekBarChangeListener(l);
        this.listener = l;
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredW = (int) (40 * density);
        int desiredH = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(
                Math.max(desiredW, resolveSize(desiredW, widthMeasureSpec)),
                resolveSize(desiredH, heightMeasureSpec));
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;

        int cx = getWidth() / 2;
        int barW = (int) (trackWidth * density);
        int thumbR = (int) (thumbRadius * density);

        int top = getPaddingTop() + thumbR;
        int bottom = getHeight() - getPaddingBottom() - thumbR;
        int barHeight = bottom - top;
        if (barHeight <= 0) return;

        int max = getMax();
        int progress = getProgress();
        int centerProgress = max / 2; // 中心位置(0 dB)

        // 1. 绘制背景轨道(圆角)
        RectF trackRect = new RectF(cx - barW / 2, top, cx + barW / 2, bottom);
        trackPaint.setColor(trackColor);
        canvas.drawRoundRect(trackRect, barW / 2, barW / 2, trackPaint);

        // 2. 绘制中心参考线(0 dB)
        float centerY = bottom - (centerProgress / (float) max) * barHeight;
        centerLinePaint.setColor(centerLineColor);
        canvas.drawCircle(cx, centerY, barW * 0.9f, centerLinePaint);

        // 3. 绘制进度条(从中心到当前值,带渐变效果)
        float progressY = bottom - (progress / (float) max) * barHeight;
        progressPaint.setColor(progressColor);

        if (progress >= centerProgress) {
            // 正值:从中心向上画
            RectF progRect = new RectF(cx - barW / 2, progressY, cx + barW / 2, centerY);
            canvas.drawRoundRect(progRect, barW / 2, barW / 2, progressPaint);
        } else {
            // 负值:从中心向下画
            RectF progRect = new RectF(cx - barW / 2, centerY, cx + barW / 2, progressY);
            canvas.drawRoundRect(progRect, barW / 2, barW / 2, progressPaint);
        }

        // 4. 绘制手柄外发光(半透明大圆)
        thumbGlowPaint.setColor(thumbGlowColor);
        canvas.drawCircle(cx, progressY, thumbR + 3 * density, thumbGlowPaint);

        // 5. 绘制手柄(白色实心圆)
        thumbPaint.setColor(thumbColor);
        canvas.drawCircle(cx, progressY, thumbR, thumbPaint);

        // 6. 手柄边框(蓝色)
        thumbBorderPaint.setColor(thumbBorderColor);
        canvas.drawCircle(cx, progressY, thumbR, thumbBorderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;

        int barHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (barHeight <= 0) return false;

        float y = event.getY();
        int max = getMax();

        // 将 Y 坐标转换为 progress(注意垂直方向是反的)
        int progress = (int) ((1f - (y - getPaddingTop()) / barHeight) * max);
        progress = Math.max(0, Math.min(max, progress));

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                setProgress(progress);
                if (listener != null) {
                    listener.onProgressChanged(this, getProgress(), true);
                    listener.onStartTrackingTouch(this);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                setProgress(progress);
                if (listener != null) {
                    listener.onProgressChanged(this, getProgress(), true);
                }
                break;
            case MotionEvent.ACTION_UP:
                setProgress(progress);
                if (listener != null) {
                    listener.onProgressChanged(this, getProgress(), false);
                    listener.onStopTrackingTouch(this);
                }
                break;
        }
        return true;
    }
}
