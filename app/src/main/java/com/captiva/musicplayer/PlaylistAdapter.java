package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;

/**
 * 播放列表弹窗(当前播放队列)的列表适配器。
 *
 * <p>数据是**队列快照**(MainActivity 传入前已 new ArrayList 拷贝):弹窗打开期间
 * MusicService 的 playList 可能被自动切歌/缓存回填改动,直接持有活引用会在
 * 滚动时踩到并发修改。真正需要同步的只有"现在是第几首在播",用
 * {@link #setPlayingIndex(int)} 单独刷新即可。
 *
 * <p>v6.6.2:ListView → RecyclerView,支持拖动排序(ItemTouchHelper 由
 * MainActivity 挂载)。排序期间行内交换走 {@link #swap(int, int)},落手时
 * MainActivity 调 MusicService.reorderQueue 一次性提交 —— 两边用同一套
 * currentIndex 调整规则,弹窗快照与服务队列保持一致。
 *
 * <p>v6.6.3:拖拽触发从"整行长按"改为"长按右侧 ≡ 把手"(整行长按在电阻屏
 * 快速滑动时极易误触发,还会搅出渲染残影);序号刷新走 payload 局部绑定 ——
 * 不带 payload 的 notifyItemRangeChanged 会触发 DefaultItemAnimator 的
 * cross-fade(旧视图淡出/新视图淡入),车机弱 GPU 上一瞬间序号整个消失,
 * 表现为"滑着的时候突然出现一个没有序号的行"。
 *
 * <p>性能:车机列表动辄 800+ 首,颜色在构造时一次性取好(不在 bind 里
 * ContextCompat.getColor);背景只按"是否当前行"换资源,不新建 Drawable 实例。
 * 注意背景不能缓存同一个 Drawable 实例跨行共享:bg_settings_item 是
 * selector(带 state_pressed),多行共用一个实例会出现"按一行、全列表一起变色"。
 */
public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.VH> {

    /** 当前播放行左侧显示的字形(代替序号,一眼看出播到哪) */
    private static final String MARK_PLAYING = "▶";

    /** 拖拽期间刷序号用的 payload:带 payload 的重绑不走 cross-fade 动画 */
    public static final Object PAYLOAD_INDEX = new Object();

    /** 行点击回调(MainActivity 收到后关弹窗并从该首起播) */
    public interface OnRowClick {
        void onRow(int position);
    }

    /** 把手长按回调(MainActivity 收到后调 ItemTouchHelper.startDrag) */
    public interface OnDragStart {
        void onDragStart(RecyclerView.ViewHolder holder);
    }

    private final List<MusicBean> data;
    private final LayoutInflater inflater;
    private final OnRowClick rowClick;
    private final OnDragStart dragStart;

    private final int colorTextPrimary;
    private final int colorTextSecondary;
    private final int colorPlayingText;
    private final int colorPlayingTextSub;

    /** 当前播放行在**队列**里的下标,-1 = 没有正在播放的(队列还没起播) */
    private int playingIndex;

    public PlaylistAdapter(Context context, List<MusicBean> data, int playingIndex,
                           OnRowClick rowClick, OnDragStart dragStart) {
        this.data = data;
        this.playingIndex = playingIndex;
        this.rowClick = rowClick;
        this.dragStart = dragStart;
        this.inflater = LayoutInflater.from(context);
        this.colorTextPrimary = ContextCompat.getColor(context, R.color.text_primary);
        this.colorTextSecondary = ContextCompat.getColor(context, R.color.text_secondary);
        this.colorPlayingText = ContextCompat.getColor(context, R.color.playing_text);
        this.colorPlayingTextSub = ContextCompat.getColor(context, R.color.playing_text_sub);
    }

    /**
     * 更新高亮行(自动切歌时由 MainActivity 的状态广播调用)。
     * 只刷新新旧两行,避免每 300ms 的进度广播把整屏重绑一遍。
     */
    public void setPlayingIndex(int index) {
        if (index == playingIndex) {
            return;
        }
        int old = playingIndex;
        playingIndex = index;
        if (old >= 0 && old < data.size()) {
            notifyItemChanged(old);
        }
        if (index >= 0 && index < data.size()) {
            notifyItemChanged(index);
        }
    }

    /**
     * 拖拽排序:把 from 行移到 to 行。
     * playingIndex 的调整规则与 MusicService.reorderQueue 完全一致
     * (拖的是播放行 → 跟着走;跨越播放位 → 补偿一格)。
     */
    public void swap(int from, int to) {
        if (from == to || from < 0 || to < 0
                || from >= data.size() || to >= data.size()) {
            return;
        }
        Collections.swap(data, from, to);
        if (playingIndex == from) {
            playingIndex = to;
        } else if (from < playingIndex && to >= playingIndex) {
            playingIndex--;
        } else if (from > playingIndex && to <= playingIndex) {
            playingIndex++;
        }
        notifyItemMoved(from, to);
        // notifyItemMoved 只重绑被移动的两行,介于 from/to 之间的行只是换了位置、
        // 不会重新 bind —— 左侧序号会停留在旧值。带 payload 局部刷新:
        // 不走 cross-fade 动画,弱 GPU 上不会出现"序号淡出一瞬间"的残影。
        int lo = Math.min(from, to);
        notifyItemRangeChanged(lo, Math.abs(from - to) + 1, PAYLOAD_INDEX);
    }

    /** 落手后整行重绑(不带 payload,把拖拽行的序号也刷成最终位置) */
    public void refreshRow(int position) {
        if (position >= 0 && position < data.size()) {
            notifyItemChanged(position);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.dialog_playlist_item, parent, false);
        return new VH(v);
    }

    /** 拖拽期间带 payload 的局部刷新:只刷序号/▶,整行不动 */
    @Override
    public void onBindViewHolder(@NonNull VH h, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            boolean playing = (position == playingIndex);
            h.index.setText(playing ? MARK_PLAYING : String.valueOf(position + 1));
            h.index.setTextColor(playing ? colorPlayingTextSub : colorTextSecondary);
            return;
        }
        onBindViewHolder(h, position);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        MusicBean bean = data.get(position);
        boolean playing = (position == playingIndex);

        if (playing) {
            h.itemView.setBackgroundResource(R.drawable.bg_playlist_item_playing);
            h.index.setText(MARK_PLAYING);
            h.index.setTextColor(colorPlayingTextSub);
            h.title.setTextColor(colorPlayingText);
            h.artist.setTextColor(colorPlayingTextSub);
            h.dur.setTextColor(colorPlayingTextSub);
            h.title.setTypeface(null, Typeface.BOLD);
        } else {
            h.itemView.setBackgroundResource(R.drawable.bg_settings_item);
            h.index.setText(String.valueOf(position + 1));
            h.index.setTextColor(colorTextSecondary);
            h.title.setTextColor(colorTextPrimary);
            h.artist.setTextColor(colorTextSecondary);
            h.dur.setTextColor(colorTextSecondary);
            h.title.setTypeface(null, Typeface.NORMAL);
        }

        h.title.setText(bean.getTitle());
        h.artist.setText(bean.getArtist());
        h.dur.setText(MusicBean.formatDuration(bean.getDuration()));

        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rowClick != null) {
                    int pos = h.getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        rowClick.onRow(pos);
                    }
                }
            }
        });

        // 拖拽把手:只有长按 ≡ 才进入拖拽模式(整行长按/滑动不再误触发)
        if (dragStart != null) {
            h.handle.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    dragStart.onDragStart(h);
                    return true;
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    /** RecyclerView 复用容器 */
    static final class VH extends RecyclerView.ViewHolder {
        final TextView index;
        final TextView title;
        final TextView artist;
        final TextView dur;
        final TextView handle;

        VH(View itemView) {
            super(itemView);
            index = (TextView) itemView.findViewById(R.id.tv_pl_index);
            title = (TextView) itemView.findViewById(R.id.tv_pl_title);
            artist = (TextView) itemView.findViewById(R.id.tv_pl_artist);
            dur = (TextView) itemView.findViewById(R.id.tv_pl_dur);
            handle = (TextView) itemView.findViewById(R.id.drag_handle);
        }
    }
}
