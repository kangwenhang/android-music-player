package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;

/**
 * 右侧 A-Z 索引条(被动显示模式)
 *
 * 车机电阻屏拖动滚轮卡顿,故改为被动模式:索引条只跟随歌曲列表的滚动显示"当前字母",
 * 不再响应触摸拖动。用户照常用手滑动列表,右侧高亮字母即当前所在字母。
 *
 * - 每个字母字号较大,便于车机辨识
 * - 有歌的字母高亮,没歌的字母置灰
 * - 由 MainActivity 在列表滚动时调用 setCurrentLetter(letter) 驱动高亮
 */
public class SideIndexBar extends View {

    /** 索引字母,A~Z 加 '#' */
    public static final String[] LETTERS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "#"
    };

    /** 每格高度(dp)。越大字母越疏、越易看清 */
    private static final float CELL_H_DP = 44f;
    /** 字母字号(sp)。在 CELL_H_DP 格子里足够大 */
    private static final float LETTER_SIZE_SP = 26f;

    /** 普通字母色(对应 text_secondary) */
    private static final int COLOR_NORMAL = Color.parseColor("#9A9AA0");
    /** 不可用字母(该字母没有歌)更暗 */
    private static final int COLOR_DISABLED = Color.parseColor("#4A4A52");
    /** 选中高亮(对应 accent) */
    private static final int COLOR_SELECTED = Color.parseColor("#4FC3F7");
    /** 中间选中带的半透明底色 */
    private static final int COLOR_BAND = Color.parseColor("#334FC3F7");

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 索引条宽度(布局未指定时的兜底) */
    private static final int DEFAULT_WIDTH_DP = 46;

    /** 每个字母是否有歌;长度与 LETTERS 一致,默认全可用 */
    private final boolean[] available = new boolean[LETTERS.length];

    private int selectedIndex = -1;

    /** 滚轮纵向滚动偏移(px)。用于把当前字母居中显示 */
    private float scrollY = 0f;
    private float cellH;
    private float minScroll;
    private float maxScroll;
    private boolean boundsReady = false;

    public SideIndexBar(Context context) {
        super(context);
        init();
    }

    public SideIndexBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SideIndexBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        for (int i = 0; i < available.length; i++) {
            available[i] = true;
        }
        float density = getResources().getDisplayMetrics().density;
        cellH = CELL_H_DP * density;
        paint.setTextSize(LETTER_SIZE_SP * getResources().getDisplayMetrics().scaledDensity);
        paint.setTextAlign(Paint.Align.CENTER);
        // 被动显示:禁用触摸,把手势交还下方歌曲列表(避免车机拖动卡顿 + 不抢列表触摸)
        setEnabled(false);
        setClickable(false);
    }

    /**
     * 标记哪些字母有歌(用于把没有歌的字母置灰)。
     *
     * @param hasSong 长度需与 {@link #LETTERS} 一致
     */
    public void setAvailableLetters(boolean[] hasSong) {
        if (hasSong == null || hasSong.length != available.length) return;
        System.arraycopy(hasSong, 0, available, 0, available.length);
        invalidate();
    }

    /** 被动模式:由列表滚动驱动,高亮并居中显示"当前字母"。letter 为单字母或 '#' */
    public void setCurrentLetter(String letter) {
        if (letter == null) return;
        int idx = -1;
        for (int i = 0; i < LETTERS.length; i++) {
            if (LETTERS[i].equals(letter)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;
        if (idx == selectedIndex) return; // 字母未变,避免重复重绘
        selectedIndex = idx;
        int h = getHeight();
        if (h > 0) {
            scrollY = idx * cellH + cellH / 2f - h / 2f;
            clampScroll();
        }
        invalidate();
    }

    private void recomputeBounds() {
        int h = getHeight();
        if (h <= 0) {
            boundsReady = false;
            return;
        }
        float contentH = LETTERS.length * cellH;
        // 让任意字母都能滚到正中:第 0 个正中时 scrollY=minScroll(可能为负),最后一个正中时=maxScroll
        minScroll = cellH / 2f - h / 2f;
        maxScroll = contentH - h / 2f - cellH / 2f;
        if (maxScroll < minScroll) maxScroll = minScroll;
        if (!boundsReady) {
            // 首次:停在 A(第 0 个正中)
            scrollY = minScroll;
            boundsReady = true;
        }
        clampScroll();
    }

    private void clampScroll() {
        if (scrollY < minScroll) scrollY = minScroll;
        if (scrollY > maxScroll) scrollY = maxScroll;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeBounds();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = resolveSize((int) (DEFAULT_WIDTH_DP * getResources().getDisplayMetrics().density), widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 中间选中带
        float bandTop = h / 2f - cellH / 2f;
        paint.setColor(COLOR_BAND);
        paint.setFakeBoldText(false);
        canvas.drawRect(0, bandTop, w, bandTop + cellH, paint);

        Paint.FontMetrics fm = paint.getFontMetrics();
        float textY = (fm.ascent + fm.descent) / 2f; // 居中用的偏移

        int first = (int) (scrollY / cellH) - 1;
        int last = (int) ((scrollY + h) / cellH) + 1;
        if (first < 0) first = 0;
        if (last >= LETTERS.length) last = LETTERS.length - 1;

        for (int i = first; i <= last; i++) {
            float cy = i * cellH - scrollY + cellH / 2f;
            if (cy < -cellH || cy > h + cellH) continue;
            if (i == selectedIndex) {
                paint.setColor(COLOR_SELECTED);
                paint.setFakeBoldText(true);
            } else if (available[i]) {
                paint.setColor(COLOR_NORMAL);
                paint.setFakeBoldText(false);
            } else {
                paint.setColor(COLOR_DISABLED);
                paint.setFakeBoldText(false);
            }
            canvas.drawText(LETTERS[i], w / 2f, cy - textY, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 被动显示模式:不消费任何触摸事件,保证歌曲列表可正常手动滑动
        return false;
    }
}
