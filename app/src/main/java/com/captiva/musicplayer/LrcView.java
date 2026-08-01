package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词显示控件(性能优化版)
 * - 封面作为底色背景(放大+模糊+暗化)
 * - 歌词叠加在封面之上
 * - 当前行高亮居中,上下行渐淡
 * - 支持自动换行(长歌词不截断)
 *
 * 性能优化:
 * 1. skipInvalidate: 列表滑动时完全跳过重绘,保留上一帧(零 CPU 开销)
 * 2. 全量布局缓存: 当前行+上下行所有 StaticLayout 缓存,避免每次 onDraw 重建
 * 3. 零分配 onDraw: 用预分配数组替代 ArrayList,消除 GC 压力
 */
public class LrcView extends View {

    private static final String TAG = "LrcView";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<LrcEntry> lrcList = new ArrayList<>();
    private int currentIndex = -1;

    // 封面背景
    private Bitmap coverBitmap;
    private Bitmap blurredBitmap;

    private final TextPaint currentPaint = new TextPaint();
    private final TextPaint normalPaint = new TextPaint();
    private final Paint coverPaint = new Paint();
    private final Paint scrimPaint = new Paint();

    // 预计算颜色常量(避免 onDraw 中每次调用 Color.parseColor → 解析开销)
    private static final int COLOR_BG_DARK = Color.parseColor("#1A1A1E");
    private static final int COLOR_NO_LRC = Color.parseColor("#99FFFFFF");
    private static final int COLOR_CURRENT = Color.parseColor("#FFFFFF");
    private static final int COLOR_NORMAL = Color.parseColor("#CCFFFFFF");
    private static final int COLOR_SHADOW_CURRENT = Color.parseColor("#AA000000");
    private static final int COLOR_SHADOW_NORMAL = Color.parseColor("#80000000");
    private static final int COLOR_SCRIM = Color.parseColor("#99000000");

    /** 行间距(每行歌词之间的间距,px) */
    private float lineSpacing = 12f;
    /** 当前行字体大小 */
    private float currentTextSize = 28f;
    /** 其他行字体大小 */
    private float normalTextSize = 22f;
    /** 最多显示行数(含当前行,上下各3行) */
    private static final int MAX_VISIBLE_LINES = 7;
    /** 上方/下方最多显示行数 */
    private static final int HALF_LINES = MAX_VISIBLE_LINES / 2;
    /** 歌词左右边距 */
    private float lrcPadding = 24f;

    // ==================== 性能优化: skipInvalidate ====================

    /** 列表滑动时设为 true,跳过所有 invalidate 调用,保留上一帧(零开销) */
    private volatile boolean skipInvalidate = false;

    /**
     * 设置是否跳过重绘(列表滑动时调用)
     * 开启后: 所有 invalidate 被忽略,View 保留最后一帧,零 CPU 开销
     * 关闭后: 立即 invalidate 一次,恢复实时更新
     */
    public void setSkipDraw(boolean skip) {
        if (this.skipInvalidate != skip) {
            this.skipInvalidate = skip;
            if (!skip) {
                // 恢复时:如果有封面但未创建模糊版本(滑动期间 setCoverBitmap 被调用),现在创建
                if (coverBitmap != null && blurredBitmap == null) {
                    try {
                        blurredBitmap = createScaledBitmap(coverBitmap);
                    } catch (OutOfMemoryError e) {
                        blurredBitmap = null;
                    }
                }
                // 强制重绘一次(补偿滑动期间跳过的所有更新)
                superInvalidate();
            }
        }
    }

    /** 内部使用的 invalidate(不受 skipInvalidate 影响) */
    private void superInvalidate() {
        super.invalidate();
    }

    @Override
    public void invalidate() {
        if (skipInvalidate) return;
        super.invalidate();
    }

    @Override
    public void invalidate(Rect rect) {
        if (skipInvalidate) return;
        super.invalidate(rect);
    }

    @Override
    public void invalidate(int l, int t, int r, int b) {
        if (skipInvalidate) return;
        super.invalidate(l, t, r, b);
    }

    // ==================== 性能优化: 全量布局缓存 ====================

    /** 缓存所有可见行的 StaticLayout(避免每次 onDraw 创建) */
    private final StaticLayout[] cachedUpLayouts = new StaticLayout[HALF_LINES];
    private final float[] cachedUpHeights = new float[HALF_LINES];
    private final StaticLayout[] cachedDownLayouts = new StaticLayout[HALF_LINES];
    private final float[] cachedDownHeights = new float[HALF_LINES];
    private StaticLayout cachedCurrentLayout;
    private float cachedCurrentHeight;

    /** 缓存有效性检查: 当这些值变化时重建所有布局 */
    private int cacheValidIndex = -2;
    private int cacheValidWidth = -1;
    private int cacheValidLrcSize = -1;

    /** 使布局缓存失效 */
    private void invalidateLayoutCache() {
        cacheValidIndex = -2;
        cacheValidWidth = -1;
        cacheValidLrcSize = -1;
        cachedCurrentLayout = null;
        for (int i = 0; i < HALF_LINES; i++) {
            cachedUpLayouts[i] = null;
            cachedDownLayouts[i] = null;
        }
    }

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
        currentPaint.setColor(COLOR_CURRENT);
        currentPaint.setTextSize(currentTextSize);
        currentPaint.setAntiAlias(true);
        // StaticLayout.ALIGN_CENTER 负责居中,Paint 用 LEFT 避免冲突
        currentPaint.setTextAlign(Paint.Align.LEFT);
        currentPaint.setShadowLayer(6f, 2f, 2f, COLOR_SHADOW_CURRENT);

        // 其他行:半透明灰
        normalPaint.setColor(COLOR_NORMAL);
        normalPaint.setTextSize(normalTextSize);
        normalPaint.setAntiAlias(true);
        normalPaint.setTextAlign(Paint.Align.LEFT);
        normalPaint.setShadowLayer(4f, 1f, 1f, COLOR_SHADOW_NORMAL);

        // 封面绘制
        coverPaint.setAntiAlias(true);
        coverPaint.setFilterBitmap(true);

        // 暗化遮罩,让歌词更清晰
        scrimPaint.setColor(COLOR_SCRIM);
    }

    /** 设置封面背景 Bitmap(缩放在后台线程执行,避免阻塞主线程) */
    public void setCoverBitmap(Bitmap bmp) {
        if (bmp == coverBitmap) {
            return;
        }
        coverBitmap = bmp;
        // 回收旧的模糊封面
        if (blurredBitmap != null) {
            blurredBitmap.recycle();
            blurredBitmap = null;
        }
        if (coverBitmap != null) {
            if (skipInvalidate) {
                // 滑动中:跳过 createScaledBitmap(耗时操作,会导致主线程卡顿)
                // blurredBitmap 保持 null,setSkipDraw(false) 恢复时会补创建
            } else {
                // 后台线程执行 createScaledBitmap(车机上约 80-140ms,不能在主线程做)
                final Bitmap src = coverBitmap;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final Bitmap scaled = createScaledBitmap(src);
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    // 检查 src 是否仍是当前封面(可能已切换歌曲)
                                    if (src == coverBitmap) {
                                        blurredBitmap = scaled;
                                        invalidate();
                                    } else if (scaled != null) {
                                        scaled.recycle();
                                    }
                                }
                            });
                        } catch (OutOfMemoryError e) {
                            // 内存不足,用纯色背景
                        }
                    }
                }).start();
            }
        } else {
            invalidate();
        }
    }

    /**
     * 将封面缩放到填满歌词显示区域(center-crop,无黑边)
     */
    private Bitmap createScaledBitmap(Bitmap src) {
        if (src == null || getWidth() <= 0 || getHeight() <= 0) {
            return null;
        }
        try {
            int vw = getWidth();
            int vh = getHeight();
            int sw = src.getWidth();
            int sh = src.getHeight();

            // center-crop:按长边缩放,填满视图(可能有裁剪但无黑边)
            float scale = Math.max((float) vw / sw, (float) vh / sh);
            int nw = (int) (sw * scale);
            int nh = (int) (sh * scale);
            if (nw < 1) nw = 1;
            if (nh < 1) nh = 1;
            return Bitmap.createScaledBitmap(src, nw, nh, true);
        } catch (OutOfMemoryError e) {
            return src;
        } catch (Exception e) {
            return src;
        }
    }

    public void setLrcList(List<LrcEntry> list) {
        // 同一引用(暂停/恢复时 service.getCurrentLrc() 返回同一个 list):不重置,避免歌词跳到开头
        if (list == lrcList) return;
        if (list == null) {
            lrcList = new ArrayList<>();
        } else {
            lrcList = list;
        }
        currentIndex = -1;
        invalidateLayoutCache();
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
            invalidateLayoutCache();
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

    /**
     * 重建所有可见行的布局缓存
     * 只在 currentIndex 或 textWidth 或 lrcList 变化时调用
     */
    private void rebuildLayoutCache(int textWidth) {
        if (lrcList == null || lrcList.isEmpty()) {
            invalidateLayoutCache();
            return;
        }

        // 当前行
        if (currentIndex < 0 || currentIndex >= lrcList.size()) {
            currentIndex = 0;
        }
        String currentText = lrcList.get(currentIndex).getText();
        currentPaint.setTextSize(currentTextSize);
        cachedCurrentLayout = createTextLayout(currentText, currentPaint, textWidth);
        cachedCurrentHeight = cachedCurrentLayout.getHeight();

        // 上方行
        normalPaint.setTextSize(normalTextSize);
        int upCount = 0;
        for (int i = currentIndex - 1; i >= 0 && upCount < HALF_LINES; i--) {
            StaticLayout layout = createTextLayout(lrcList.get(i).getText(), normalPaint, textWidth);
            cachedUpLayouts[upCount] = layout;
            cachedUpHeights[upCount] = layout.getHeight();
            upCount++;
        }
        // 清空多余的槽位
        for (int i = upCount; i < HALF_LINES; i++) {
            cachedUpLayouts[i] = null;
        }

        // 下方行
        int downCount = 0;
        for (int i = currentIndex + 1; i < lrcList.size() && downCount < HALF_LINES; i++) {
            StaticLayout layout = createTextLayout(lrcList.get(i).getText(), normalPaint, textWidth);
            cachedDownLayouts[downCount] = layout;
            cachedDownHeights[downCount] = layout.getHeight();
            downCount++;
        }
        // 清空多余的槽位
        for (int i = downCount; i < HALF_LINES; i++) {
            cachedDownLayouts[i] = null;
        }

        cacheValidIndex = currentIndex;
        cacheValidWidth = textWidth;
        cacheValidLrcSize = lrcList.size();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long t0 = PerfLogger.isEnabled() ? System.currentTimeMillis() : 0;
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
            canvas.drawColor(COLOR_BG_DARK);
            canvas.drawRect(0, 0, vw, vh, scrimPaint);
        }

        // 2. 绘制歌词
        if (lrcList == null || lrcList.isEmpty()) {
            currentPaint.setTextSize(normalTextSize);
            currentPaint.setColor(COLOR_NO_LRC);
            canvas.drawText("暂无歌词", vw / 2f, vh / 2f, currentPaint);
            currentPaint.setColor(COLOR_CURRENT);
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

        // 检查缓存是否有效,无效则重建(避免每次 onDraw 都创建 StaticLayout)
        if (cacheValidIndex != currentIndex
                || cacheValidWidth != textWidth
                || cacheValidLrcSize != lrcList.size()
                || cachedCurrentLayout == null) {
            rebuildLayoutCache(textWidth);
        }

        // StaticLayout 从 x=0 开始绘制, ALIGN_CENTER 使文字在 [0, textWidth] 内居中
        // 所以文字中心在 textWidth/2 处, 要让文字中心对齐屏幕中心 cx,
        // 需 translate 到 cx - textWidth/2
        float layoutX = cx - textWidth / 2f;

        // 当前行垂直居中:当前行的中心在 cy
        float currentTop = cy - cachedCurrentHeight / 2f;

        // 从下往上绘制上方行(离当前行最近的先算位置)
        float drawY = currentTop;
        for (int i = 0; i < HALF_LINES; i++) {
            if (cachedUpLayouts[i] == null) break;
            float h = cachedUpHeights[i];
            drawY -= h + lineSpacing;
            if (drawY < -h) break; // 超出屏幕顶部
            canvas.save();
            canvas.translate(layoutX, drawY);
            cachedUpLayouts[i].draw(canvas);
            canvas.restore();
        }

        // 绘制当前行
        canvas.save();
        canvas.translate(layoutX, currentTop);
        cachedCurrentLayout.draw(canvas);
        canvas.restore();

        // 绘制下方行
        drawY = currentTop + cachedCurrentHeight + lineSpacing;
        for (int i = 0; i < HALF_LINES; i++) {
            if (cachedDownLayouts[i] == null) break;
            float h = cachedDownHeights[i];
            if (drawY > vh) break; // 超出屏幕底部
            canvas.save();
            canvas.translate(layoutX, drawY);
            cachedDownLayouts[i].draw(canvas);
            canvas.restore();
            drawY += h + lineSpacing;
        }

        if (PerfLogger.isEnabled()) {
            PerfLogger.log("LrcDraw", System.currentTimeMillis() - t0);
        }
    }
}
