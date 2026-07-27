package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词显示控件
 * - 封面作为底色背景(放大+模糊+暗化)
 * - 歌词叠加在封面之上
 * - 当前行高亮居中,上下行渐淡
 */
public class LrcView extends View {

    private List<LrcEntry> lrcList = new ArrayList<>();
    private int currentIndex = -1;

    // 封面背景
    private Bitmap coverBitmap;
    private Bitmap blurredBitmap;

    private final Paint currentPaint = new Paint();
    private final Paint normalPaint = new Paint();
    private final Paint coverPaint = new Paint();
    private final Paint scrimPaint = new Paint();

    private float lineHeight = 80f;
    private float currentTextSize = 42f;
    private float normalTextSize = 32f;
    /** 最多显示行数(含当前行,上下各2行) */
    private static final int MAX_VISIBLE_LINES = 5;

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
        // 当前行:亮色高亮
        currentPaint.setColor(Color.parseColor("#FFFFFF"));
        currentPaint.setTextSize(currentTextSize);
        currentPaint.setAntiAlias(true);
        currentPaint.setTextAlign(Paint.Align.CENTER);
        currentPaint.setShadowLayer(6f, 2f, 2f, Color.parseColor("#AA000000"));

        // 其他行:半透明灰
        normalPaint.setColor(Color.parseColor("#CCFFFFFF"));
        normalPaint.setTextSize(normalTextSize);
        normalPaint.setAntiAlias(true);
        normalPaint.setTextAlign(Paint.Align.CENTER);
        normalPaint.setShadowLayer(4f, 1f, 1f, Color.parseColor("#80000000"));

        // 封面绘制
        coverPaint.setAntiAlias(true);
        coverPaint.setFilterBitmap(true);

        // 暗化遮罩,让歌词更清晰
        scrimPaint.setColor(Color.parseColor("#99000000"));
    }

    /** 设置封面背景 Bitmap */
    public void setCoverBitmap(Bitmap bmp) {
        if (bmp == coverBitmap) {
            return;
        }
        coverBitmap = bmp;
        // 生成放大模糊版本
        if (blurredBitmap != null) {
            blurredBitmap.recycle();
            blurredBitmap = null;
        }
        if (coverBitmap != null) {
            try {
                blurredBitmap = createScaledBitmap(coverBitmap);
            } catch (OutOfMemoryError e) {
                // 车机内存不足时,直接用原图,不模糊
                blurredBitmap = null;
            }
        }
        invalidate();
    }

    /** 将封面放大到填满视图,并做简单模糊 */
    private Bitmap createScaledBitmap(Bitmap src) {
        if (src == null || getWidth() <= 0 || getHeight() <= 0) {
            return null;
        }
        try {
            // 放大到填满(居中裁剪)
            int vw = getWidth();
            int vh = getHeight();
            int sw = src.getWidth();
            int sh = src.getHeight();
            float scale = Math.max((float) vw / sw, (float) vh / sh);
            int nw = (int) (sw * scale);
            int nh = (int) (sh * scale);
            // 车机内存有限且性能弱,限制最大尺寸并跳过模糊
            int maxDim = 400;
            if (nw > maxDim || nh > maxDim) {
                float ratio = (float) maxDim / Math.max(nw, nh);
                nw = (int) (nw * ratio);
                nh = (int) (nh * ratio);
            }
            // 性能优化:车机性能弱,直接缩放不做模糊
            return Bitmap.createScaledBitmap(src, nw, nh, true);
        } catch (OutOfMemoryError e) {
            // 内存不足,直接返回原图
            return src;
        } catch (Exception e) {
            return src;
        }
    }

    /** 快速模糊:先缩小再放大,产生模糊效果(兼容 API 14) */
    private Bitmap fastBlur(Bitmap src, float sampleSize) {
        if (src == null) return null;
        try {
            int w = Math.max(1, (int) (src.getWidth() * sampleSize));
            int h = Math.max(1, (int) (src.getHeight() * sampleSize));
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
            Bitmap blurred = Bitmap.createScaledBitmap(small, src.getWidth(), src.getHeight(), true);
            if (small != blurred && small != src) {
                small.recycle();
            }
            return blurred;
        } catch (OutOfMemoryError e) {
            return src;
        } catch (Exception e) {
            return src;
        }
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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 尺寸变化时重新生成模糊封面
        if (coverBitmap != null) {
            if (blurredBitmap != null) {
                blurredBitmap.recycle();
                blurredBitmap = null;
            }
            blurredBitmap = createScaledBitmap(coverBitmap);
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int vw = getWidth();
        int vh = getHeight();

        // 1. 绘制封面背景(放大模糊+暗化)
        if (blurredBitmap != null) {
            // 居中裁剪绘制
            int bw = blurredBitmap.getWidth();
            int bh = blurredBitmap.getHeight();
            int x = (vw - bw) / 2;
            int y = (vh - bh) / 2;
            canvas.drawBitmap(blurredBitmap, x, y, coverPaint);
            // 暗化遮罩
            canvas.drawRect(0, 0, vw, vh, scrimPaint);
        } else {
            // 无封面时用纯色背景
            canvas.drawColor(Color.parseColor("#1A1A1E"));
            canvas.drawRect(0, 0, vw, vh, scrimPaint);
        }

        // 2. 绘制歌词
        if (lrcList == null || lrcList.isEmpty()) {
            currentPaint.setTextSize(normalTextSize);
            currentPaint.setColor(Color.parseColor("#99FFFFFF"));
            canvas.drawText("暂无歌词", vw / 2f, vh / 2f, currentPaint);
            currentPaint.setColor(Color.parseColor("#FFFFFF"));
            return;
        }

        float cx = vw / 2f;
        float cy = vh / 2f;

        // 当前行居中
        if (currentIndex < 0 || currentIndex >= lrcList.size()) {
            currentIndex = 0;
        }

        // 绘制上方行(最多显示2行)
        int upLimit = MAX_VISIBLE_LINES / 2;
        for (int i = currentIndex; i >= 0 && upLimit >= 0; i--) {
            float y = cy - (currentIndex - i) * lineHeight;
            if (y < -lineHeight) break;
            if (i == currentIndex) {
                canvas.drawText(lrcList.get(i).getText(), cx, y, currentPaint);
            } else {
                canvas.drawText(lrcList.get(i).getText(), cx, y, normalPaint);
                upLimit--;
            }
        }

        // 绘制下方行(最多显示2行)
        int downLimit = MAX_VISIBLE_LINES / 2;
        for (int i = currentIndex + 1; i < lrcList.size() && downLimit > 0; i++) {
            float y = cy + (i - currentIndex) * lineHeight;
            if (y > vh + lineHeight) break;
            canvas.drawText(lrcList.get(i).getText(), cx, y, normalPaint);
            downLimit--;
        }
    }
}
