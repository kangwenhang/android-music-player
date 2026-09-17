package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 右侧 A-Z 快速索引条
 *
 * 手指按下或拖动即可跳到对应字母的歌曲,抬起后高亮消失。
 * 共 27 格:A~Z 加一个 #(数字 / 符号 / 生僻字)。
 *
 * 车机适配要点(针对电阻屏 + 横屏 1024x600 反复打磨):
 *   1. 条更宽(布局给 48dp,兜底 46dp)、字母更大(16sp 起步),低矮屏上也不会被压成针尖。
 *   2. 半透明浮在列表之上(见 activity_main.xml 的 bg_side_index),不占列表宽度。
 *   3. 每次切到新字母给一下轻震动(无需权限),操作有确定感。
 *   4. 触摸判定整格响应,且 MainActivity 里用 TouchDelegate 把命中区向左侧列表方向扩出 ~18dp,
 *      手指稍微偏出条外也能被捕获。
 *   5. 居中大字母提示由布局里的 tv_index_letter 负责(本类不重复实现)。
 */
public class SideIndexBar extends View {

    /** 索引字母,A~Z 加 '#' */
    public static final String[] LETTERS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "#"
    };

    /** 普通字母色(对应 text_secondary) */
    private static final int COLOR_NORMAL = Color.parseColor("#9A9AA0");
    /** 不可用字母(该字母没有歌)更暗 */
    private static final int COLOR_DISABLED = Color.parseColor("#4A4A52");
    /** 选中高亮(对应 accent) */
    private static final int COLOR_SELECTED = Color.parseColor("#4FC3F7");

    public interface OnLetterChangedListener {
        /** 手指按下或滑到某个字母;letter 一定是可用的(已在不可用字母上做过最近邻修正) */
        void onLetterChanged(String letter);

        /** 手指抬起 */
        void onTouchUp();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * 索引条宽度(布局未指定时的兜底)。车机电阻屏不能太窄——太窄字母小且极难点中。
     * 布局侧用 @dimen/index_bar_width(48dp) 指定,这里只是兜底。
     */
    private static final int DEFAULT_WIDTH_DP = 46;

    /** 每个字母是否有歌;长度与 LETTERS 一致,默认全可用 */
    private final boolean[] available = new boolean[LETTERS.length];

    private OnLetterChangedListener listener;
    private int selectedIndex = -1;
    /** 期望基础字号;绘制时还会按格子高度收敛,防止低矮车机屏上字母重叠 */
    private float textSize;

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
        // 基础字号 16sp(原 11sp)。绘制时再按格子高度收敛,但上限放宽,低矮屏也不致太小。
        textSize = 16f * getResources().getDisplayMetrics().scaledDensity;
        paint.setTextSize(textSize);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnLetterChangedListener(OnLetterChangedListener l) {
        this.listener = l;
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

        float cellH = h / (float) LETTERS.length;
        float cx = w / 2f;

        // 字号按格子高度收敛,但上限放宽到 0.86,且不低于 12sp,保证可读。
        float maxByCell = cellH * 0.86f;
        float minSize = 12f * getResources().getDisplayMetrics().scaledDensity;
        float size = Math.max(minSize, Math.min(textSize, maxByCell));
        paint.setTextSize(size);
        Paint.FontMetrics fm = paint.getFontMetrics();

        for (int i = 0; i < LETTERS.length; i++) {
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
            float cy = cellH * i + cellH / 2f;
            float baseY = cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(LETTERS[i], cx, baseY, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                setPressed(true);
                handleTouch(event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                handleTouch(event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                selectedIndex = -1;
                invalidate();
                if (listener != null) listener.onTouchUp();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void handleTouch(float y) {
        int idx = indexForY(y);
        if (idx < 0) return;
        // 落在没有歌的字母上,就近找一个有歌的字母,避免点了没反应
        int resolved = idx;
        if (!available[idx]) {
            resolved = nearestAvailable(idx);
            if (resolved < 0) return;
        }
        if (resolved != selectedIndex) {
            selectedIndex = resolved;
            invalidate();
            // 电阻屏轻震动,操作有确定感(无需权限)
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onLetterChanged(LETTERS[resolved]);
        }
    }

    /** 屏幕上某个 y 坐标对应第几个字母 */
    private int indexForY(float y) {
        int h = getHeight();
        if (h <= 0) return -1;
        float cellH = h / (float) LETTERS.length;
        int idx = (int) (y / cellH);
        if (idx < 0) idx = 0;
        if (idx >= LETTERS.length) idx = LETTERS.length - 1;
        return idx;
    }

    /** 从 index 向两侧找最近的有歌字母 */
    private int nearestAvailable(int index) {
        for (int d = 1; d < LETTERS.length; d++) {
            int up = index - d;
            int down = index + d;
            if (down < LETTERS.length && available[down]) return down;
            if (up >= 0 && available[up]) return up;
        }
        return -1;
    }
}
