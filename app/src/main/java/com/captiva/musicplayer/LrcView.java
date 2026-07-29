package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词显示控件
 * - 封面作为底色背景(放大+模糊+暗化)
 * - 歌词叠加在封面之上
 * - 当前行高亮居中,上下行渐淡
 * - 支持自动换行(长歌词不截断)
 */
public class LrcView extends View {

    private List<LrcEntry> lrcList = new ArrayList<>();
    private int currentIndex = -1;

    // 封面背景
    private Bitmap coverBitmap;
    private Bitmap blurredBitmap;

    private final TextPaint currentPaint = new TextPaint();
    private final TextPaint normalPaint = new TextPaint();
    private final Paint coverPaint = new Paint();
    private final Paint scrimPaint = new Paint();

    /** 行间距(每行歌词之间的间距,px) */
    private float lineSpacing = 12f;
    /** 当前行字体大小 */
    private float currentTextSize = 24f;
    /** 其他行字体大小 */
    private float normalTextSize = 18f;
    /** 最多显示行数(含当前行,上下各2行) */
    private static final int MAX_VISIBLE_LINES = 5;
    /** 歌词左右边距 */
    private float lrcPadding = 24f;

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

    /** 将封面放大到填满视图(高清,不过度放大) */
    private Bitmap createScaledBitmap(Bitmap src) {
        if (src == null || getWidth() <= 0 || getHeight() <= 0) {
            return null;
        }
        try {
            int vw = getWidth();
            int vh = getHeight();
            int sw = src.getWidth();
            int sh = src.getHeight();

            // 计算填满视图所需的缩放比例(center-crop)
            float scale = Math.max((float) vw / sw, (float) vh / sh);

            // 如果原图已经大于视图,不放大(避免不必要的内存消耗)
            if (scale > 1.0f) {
                // 原图小于视图,需要放大
                int nw = (int) (sw * scale);
                int nh = (int) (sh * scale);
                // 限制最大尺寸为视图尺寸的1.2倍(刚好填满即可,不过度放大)
                int maxDim = (int) (Math.max(vw, vh) * 1.2f);
                if (nw > maxDim || nh > maxDim) {
                    float ratio = (float) maxDim / Math.max(nw, nh);
                    nw = (int) (nw * ratio);
                    nh = (int) (nh * ratio);
                }
                return Bitmap.createScaledBitmap(src, nw, nh, true);
            } else {
                // 原图已够大,直接裁剪使用(不缩小,保持清晰度)
                return src;
            }
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

    /**
     * 为指定文本创建 StaticLayout(支持自动换行)
     * @param text   歌词文本
     * @param paint  画笔(决定字体大小和颜色)
     * @param width  可用宽度
     * @return StaticLayout
     */
    private StaticLayout createTextLayout(String text, TextPaint paint, int width) {
        if (width <= 0) {
            width = getWidth();
        }
        if (width <= 0) {
            width = 400;
        }
        return new StaticLayout(
                text,
                0,
                text.length(),
                paint,
                width,
                Layout.Alignment.ALIGN_CENTER,
                1.0f,
                0f,
                false);
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

        // 可用宽度(减去左右边距)
        int textWidth = vw - (int)(lrcPadding * 2);
        if (textWidth <= 0) textWidth = vw;

        // 计算当前行的高度(可能换行)
        String currentText = lrcList.get(currentIndex).getText();
        currentPaint.setTextSize(currentTextSize);
        StaticLayout currentLayout = createTextLayout(currentText, currentPaint, textWidth);
        float currentHeight = currentLayout.getHeight();

        // 当前行垂直居中:当前行的中心在 cy
        // 当前行顶部在 cy - currentHeight/2
        float currentTop = cy - currentHeight / 2f;

        // 收集上方行(最多2行)
        List<StaticLayout> upLayouts = new ArrayList<>();
        List<Float> upHeights = new ArrayList<>();
        normalPaint.setTextSize(normalTextSize);
        int upCount = 0;
        int upLimit = MAX_VISIBLE_LINES / 2;
        for (int i = currentIndex - 1; i >= 0 && upCount < upLimit; i--) {
            StaticLayout layout = createTextLayout(lrcList.get(i).getText(), normalPaint, textWidth);
            upLayouts.add(layout);
            upHeights.add((float) layout.getHeight());
            upCount++;
        }

        // 收集下方行(最多2行)
        List<StaticLayout> downLayouts = new ArrayList<>();
        List<Float> downHeights = new ArrayList<>();
        int downCount = 0;
        int downLimit = MAX_VISIBLE_LINES / 2;
        for (int i = currentIndex + 1; i < lrcList.size() && downCount < downLimit; i++) {
            StaticLayout layout = createTextLayout(lrcList.get(i).getText(), normalPaint, textWidth);
            downLayouts.add(layout);
            downHeights.add((float) layout.getHeight());
            downCount++;
        }

        // 从下往上绘制上方行(离当前行最近的先算位置)
        float drawY = currentTop;
        for (int i = upLayouts.size() - 1; i >= 0; i--) {
            float h = upHeights.get(i);
            drawY -= h + lineSpacing;
            if (drawY < -h) break; // 超出屏幕顶部
            StaticLayout layout = upLayouts.get(i);
            canvas.save();
            canvas.translate(cx, drawY);
            layout.draw(canvas);
            canvas.restore();
        }

        // 绘制当前行
        canvas.save();
        canvas.translate(cx, currentTop);
        currentLayout.draw(canvas);
        canvas.restore();

        // 绘制下方行
        drawY = currentTop + currentHeight + lineSpacing;
        for (int i = 0; i < downLayouts.size(); i++) {
            float h = downHeights.get(i);
            if (drawY > vh) break; // 超出屏幕底部
            StaticLayout layout = downLayouts.get(i);
            canvas.save();
            canvas.translate(cx, drawY);
            layout.draw(canvas);
            canvas.restore();
            drawY += h + lineSpacing;
        }
    }
}
