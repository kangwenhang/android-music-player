package com.captiva.musicplayer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/**
 * 有界测量 RecyclerView(2026-10-05 v430 卡顿根治)。
 *
 * 【背景】车机/模拟器实测(download_debug 23/425-429):切歌、缓存完成等触发
 * 全窗口 measure 时,权重容器(LinearLayout 0dp+weight 链)会把本 RV 以
 * UNSPECIFIED/AT_MOST 模式测量一次。LinearLayoutManager 在无界高度下会把
 * 整张列表(811 行)逐行 onCreateViewHolder+onBindViewHolder 挂载一遍
 * (车机 Dalvik 上每行 30-80ms → 单次 8-20 秒主线程占用),随后真实 EXACT
 * 测量再把 800+ 个 holder 全部回收 —— 这就是"创建风暴"(创建≈回收≈绑定、
 * 回收池永远只有 5 个)与秒级卡顿的真凶。
 *
 * 【修复】onMeasure 时若父容器给了非 EXACTLY 的模式,改用上一次 EXACT 测量
 * 的已知尺寸强制按 EXACTLY 测量,杜绝无界 pass。首次布局前用 1px 占位,
 * 首次 EXACT 测量后即有正确值。
 */
public class BoundedRecyclerView extends RecyclerView {

    private int mLastExactWidth = -1;
    private int mLastExactHeight = -1;

    public BoundedRecyclerView(Context context) {
        super(context);
    }

    public BoundedRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BoundedRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int wMode = View.MeasureSpec.getMode(widthSpec);
        int hMode = View.MeasureSpec.getMode(heightSpec);
        int wSize = View.MeasureSpec.getSize(widthSpec);
        int hSize = View.MeasureSpec.getSize(heightSpec);

        // 宽度无界 → 用已知宽度强制 EXACTLY
        if (wMode != View.MeasureSpec.EXACTLY) {
            int w = (wMode == View.MeasureSpec.AT_MOST && wSize > 0 && wSize < Integer.MAX_VALUE)
                    ? wSize : mLastExactWidth;
            if (w <= 0) {
                w = 1;
            }
            widthSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY);
        } else if (wSize > 0) {
            mLastExactWidth = wSize;
        }

        // 高度无界 → 用已知高度强制 EXACTLY
        if (hMode != View.MeasureSpec.EXACTLY) {
            int h = (hMode == View.MeasureSpec.AT_MOST && hSize > 0 && hSize < Integer.MAX_VALUE)
                    ? hSize : mLastExactHeight;
            if (h <= 0) {
                h = 1;
            }
            heightSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY);
        } else if (hSize > 0) {
            mLastExactHeight = hSize;
        }

        super.onMeasure(widthSpec, heightSpec);
    }
}
