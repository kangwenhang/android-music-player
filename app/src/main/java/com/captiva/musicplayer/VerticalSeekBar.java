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
 * 自定义绘制:圆角轨道 + 蓝色进度条 + 中心线
 * 兼容 API 17+
 */
public class VerticalSeekBar extends SeekBar {

    private Paint trackPaint;
    private Paint progressPaint;
    private Paint centerLinePaint;
    private Paint thumbPaint;
    private Paint thumbBorderPaint;

    private int trackColor = 0xFF2A2A30;
    private int progressColor = 0xFF4FC3F7;
    private int centerLineColor = 0xFF4A4A52;
    private int thumbColor = 0xFFFFFFFF;
    private int thumbBorderColor = 0xFF4FC3F7;

    private int trackWidth = 8;  // dp
    private int thumbRadius = 12; // dp

    private float density;

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

        // 隐藏系统默认的 thumb 和 progress
        setThumb(null);
        setProgressDrawable(null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 交换宽高:让 SeekBar 在视觉上是垂直的
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        // 设定最小宽度
        int desiredW = (int) (40 * density);
        int desiredH = h;
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

        // 1. 绘制背景轨道
        RectF trackRect = new RectF(cx - barW / 2, top, cx + barW / 2, bottom);
        trackPaint.setColor(trackColor);
        canvas.drawRoundRect(trackRect, barW / 2, barW / 2, trackPaint);

        // 2. 绘制中心线(0 dB 参考线)
        float centerY = bottom - (centerProgress / (float) max) * barHeight;
        centerLinePaint.setColor(centerLineColor);
        canvas.drawCircle(cx, centerY, barW * 0.8f, centerLinePaint);

        // 3. 绘制进度条(从中心到当前值)
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

        // 4. 绘制 Thumb(圆形手柄)
        thumbPaint.setColor(thumbColor);
        canvas.drawCircle(cx, progressY, thumbR, thumbPaint);
        // Thumb 边框
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
                notifyProgressChanged(true);
                notifyStartTracking();
                break;
            case MotionEvent.ACTION_MOVE:
                setProgress(progress);
                notifyProgressChanged(true);
                break;
            case MotionEvent.ACTION_UP:
                setProgress(progress);
                notifyProgressChanged(false);
                notifyStopTracking();
                break;
        }
        return true;
    }

    private void notifyProgressChanged(boolean fromUser) {
        OnSeekBarChangeListener listener = getOnSeekBarChangeListener();
        if (listener != null) {
            listener.onProgressChanged(this, getProgress(), fromUser);
        }
    }

    /** 在 ACTION_DOWN 时调用 */
    private void notifyStartTracking() {
        OnSeekBarChangeListener listener = getOnSeekBarChangeListener();
        if (listener != null) {
            listener.onStartTrackingTouch(this);
        }
    }

    /** 在 ACTION_UP 时调用 */
    private void notifyStopTracking() {
        OnSeekBarChangeListener listener = getOnSeekBarChangeListener();
        if (listener != null) {
            listener.onStopTrackingTouch(this);
        }
    }
}
