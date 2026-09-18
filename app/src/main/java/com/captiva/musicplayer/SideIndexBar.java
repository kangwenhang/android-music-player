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
 * 右侧 A-Z 快速索引条(可滚动大字母滚轮)
 *
 * 车机电阻屏优化核心:27 个字母太多,挤在矮屏上一字才十几像素,根本看不清也点不准。
 * 所以把它做成一根可上下拖动的"字母滚轮":
 *   - 每个字母字号很大(LETTER_SIZE_SP,约 26sp),单字母占一格(CELL_H_DP 高)
 *   - 中间固定一条选中线,滚到哪个字母就跳到哪
 *   - 手指拖动即滚动,松手吸附到最近的字母
 *   - 有歌的字母才可选中,落在没歌的字母上会自动吸附到最近的有歌字母
 *
 * 居中大字母提示已由本滚轮自身承担(高亮 + 选中线),不再依赖布局里的 tv_index_letter。
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

    public interface OnLetterChangedListener {
        /** 滚轮滚到某个字母;letter 一定是可用的(已在不可用字母上做过最近邻吸附) */
        void onLetterChanged(String letter);

        /** 手指抬起 */
        void onTouchUp();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 索引条宽度(布局未指定时的兜底) */
    private static final int DEFAULT_WIDTH_DP = 46;

    /** 每个字母是否有歌;长度与 LETTERS 一致,默认全可用 */
    private final boolean[] available = new boolean[LETTERS.length];

    private OnLetterChangedListener listener;
    private int selectedIndex = -1;

    /** 滚轮纵向滚动偏移(px)。可负:使第 0 个字母也能滚到正中 */
    private float scrollY = 0f;
    private float cellH;
    private float minScroll;
    private float maxScroll;
    private boolean boundsReady = false;

    private float lastTouchY;

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

    /** 根据当前尺寸算滚动边界,并把 scrollY 夹在合法范围 */
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

    /** 由当前 scrollY 推算正中的字母下标(落空则吸附到最近的有歌字母) */
    private int getSelectedIndex() {
        int h = getHeight();
        if (h <= 0) return -1;
        float fpos = (scrollY + h / 2f) / cellH;
        int raw = Math.round(fpos);
        if (raw < 0) raw = 0;
        if (raw >= LETTERS.length) raw = LETTERS.length - 1;
        if (available[raw]) return raw;
        return nearestAvailable(raw);
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
        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                setPressed(true);
                lastTouchY = event.getY();
                recomputeBounds();
                reportIfChanged();
                return true;
            case MotionEvent.ACTION_MOVE:
                float y = event.getY();
                float delta = y - lastTouchY;
                lastTouchY = y;
                // 内容跟随手指:手指下移 -> 内容上移 -> scrollY 减小
                scrollY -= delta;
                clampScroll();
                reportIfChanged();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                snapToSelected();
                invalidate();
                if (listener != null) listener.onTouchUp();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** 选中下标变化时才回调(并轻震动) */
    private void reportIfChanged() {
        int idx = getSelectedIndex();
        if (idx < 0) return;
        if (idx != selectedIndex) {
            selectedIndex = idx;
            invalidate();
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onLetterChanged(LETTERS[idx]);
        }
    }

    /** 松手吸附:把 scrollY 对齐到当前选中字母的正中,滚轮感更稳 */
    private void snapToSelected() {
        int h = getHeight();
        if (h <= 0 || selectedIndex < 0) return;
        scrollY = selectedIndex * cellH + cellH / 2f - h / 2f;
        clampScroll();
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
