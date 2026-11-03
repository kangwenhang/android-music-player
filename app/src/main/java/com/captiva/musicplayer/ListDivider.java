package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/**
 * 列表分隔线(v6.4):每条 item 底部画 1dp 细线。
 *
 * 颜色取 @color/list_divider,随 DayNight 主题自动昼夜切换。
 * 之所以不用 support 库的 DividerItemDecoration:它默认吃主题的
 * android:listDivider 属性,车机低版本 ROM 上该属性可能未定义,
 * 且 setDrawable 在老版本 recyclerview-v7 里不存在 —— 自绘最可控。
 *
 * 性能:onDrawOver 每帧只遍历可视 child(车机一屏 5-6 条)画等宽矩形,
 * 无对象创建(Paint/Rect 全部复用),对弱 CPU 无感。
 */
public final class ListDivider extends RecyclerView.ItemDecoration {

    private final Paint paint = new Paint();
    private final int heightPx;

    public ListDivider(Context ctx) {
        paint.setColor(ctx.getResources().getColor(R.color.list_divider));
        // 1dp,取整后至少 1px,避免低密度屏画不出线
        heightPx = Math.max(1,
                (int) (ctx.getResources().getDisplayMetrics().density + 0.5f));
    }

    /** 给每条 item 底部预留 1dp 空隙,线画在空隙里,不叠在内容上 */
    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                               RecyclerView.State state) {
        outRect.bottom = heightPx;
    }

    @Override
    public void onDrawOver(Canvas c, RecyclerView parent, RecyclerView.State state) {
        final int left = parent.getPaddingLeft();
        final int right = parent.getWidth() - parent.getPaddingRight();
        final int n = parent.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = parent.getChildAt(i);
            RecyclerView.LayoutParams lp =
                    (RecyclerView.LayoutParams) child.getLayoutParams();
            final int top = child.getBottom() + lp.bottomMargin;
            c.drawRect(left, top, right, top + heightPx, paint);
        }
    }
}
