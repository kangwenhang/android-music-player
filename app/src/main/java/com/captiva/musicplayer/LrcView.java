package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词显示控件
 * 当前行高亮居中,上下行渐淡
 */
public class LrcView extends View {

    private List<LrcEntry> lrcList = new ArrayList<>();
    private int currentIndex = -1;

    private final Paint currentPaint = new Paint();
    private final Paint normalPaint = new Paint();

    private float lineHeight = 72f;
    private float currentTextSize = 32f;
    private float normalTextSize = 24f;

    public LrcView(Context context) {
        super(context);
        init();
    }

    public LrcView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LrcView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        currentPaint.setColor(Color.parseColor("#4FC3F7"));
        currentPaint.setTextSize(currentTextSize);
        currentPaint.setAntiAlias(true);
        currentPaint.setTextAlign(Paint.Align.CENTER);

        normalPaint.setColor(Color.parseColor("#9A9AA0"));
        normalPaint.setTextSize(normalTextSize);
        normalPaint.setAntiAlias(true);
        normalPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setLrcList(List<LrcEntry> list) {
        if (list == null) {
            lrcList = new ArrayList<>();
        } else {
            lrcList = list;
        }
        currentIndex = -1;
        invalidate();
    }

    public void setCurrentIndex(int index) {
        if (index != currentIndex) {
            currentIndex = index;
            invalidate();
        }
    }

    public boolean hasLrc() {
        return lrcList != null && !lrcList.isEmpty();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lrcList == null || lrcList.isEmpty()) {
            currentPaint.setTextSize(normalTextSize);
            canvas.drawText("暂无歌词", getWidth() / 2f, getHeight() / 2f, currentPaint);
            return;
        }

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        // 当前行居中
        if (currentIndex < 0 || currentIndex >= lrcList.size()) {
            currentIndex = 0;
        }
        canvas.drawText(lrcList.get(currentIndex).getText(), cx, cy, currentPaint);

        // 绘制上方行
        for (int i = currentIndex - 1; i >= 0; i--) {
            float y = cy - (currentIndex - i) * lineHeight;
            if (y < -lineHeight) break;
            canvas.drawText(lrcList.get(i).getText(), cx, y, normalPaint);
        }
        // 绘制下方行
        for (int i = currentIndex + 1; i < lrcList.size(); i++) {
            float y = cy + (i - currentIndex) * lineHeight;
            if (y > getHeight() + lineHeight) break;
            canvas.drawText(lrcList.get(i).getText(), cx, y, normalPaint);
        }
    }
}
