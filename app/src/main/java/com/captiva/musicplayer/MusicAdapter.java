package com.captiva.musicplayer;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌曲列表适配器
 * - 封面图异步加载
 * - 本地/网络来源标识
 * - 搜索过滤
 * - 分批加载(防止大量数据卡死车机)
 */
public class MusicAdapter extends RecyclerView.Adapter<MusicAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(int position, MusicBean bean);
    }

    /** 滚动加载每批数量 */
    private static final int BATCH_SIZE = 100;

    private final List<MusicBean> fullData = new ArrayList<>();  // 完整列表
    private final List<MusicBean> filteredData = new ArrayList<>(); // 过滤后的完整列表
    private final List<MusicBean> data = new ArrayList<>();       // 当前显示列表(分批加载)
    private final Context context;
    private OnItemClickListener listener;
    private int playingIndex = -1;
    private String filterKeyword = "";

    /** 当前已加载到第几条(分批加载) */
    private int loadedCount = 0;
    /** 是否还有更多数据可加载 */
    private boolean hasMore = false;
    /** 是否正在加载 */
    private boolean isLoading = false;

    public MusicAdapter(Context context) {
        this.context = context;
    }

    public void setData(List<MusicBean> list) {
        fullData.clear();
        if (list != null) {
            fullData.addAll(list);
        }
        loadedCount = 0;
        data.clear();
        applyFilterAndLoadFirstBatch();
    }

    /** 追加数据(后台分页加载用,不刷新整个列表) */
    public void appendData(List<MusicBean> more) {
        if (more == null || more.isEmpty()) {
            return;
        }
        fullData.addAll(more);
        // 如果没有搜索过滤,直接追加到显示列表
        if (filterKeyword.isEmpty()) {
            filteredData.addAll(more);
            int start = data.size();
            // 只加载到当前批次限制
            int canLoad = Math.min(more.size(), BATCH_SIZE - (data.size() - loadedCount));
            if (canLoad > 0) {
                for (int i = 0; i < canLoad && loadedCount < filteredData.size(); i++) {
                    data.add(filteredData.get(loadedCount));
                    loadedCount++;
                }
                hasMore = loadedCount < filteredData.size();
                notifyItemRangeInserted(start, canLoad);
            }
        }
        // 如果有搜索过滤,filteredData会在下次filter时重建
    }

    /** 搜索过滤 */
    public void filter(String keyword) {
        filterKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
        loadedCount = 0;
        data.clear();
        applyFilterAndLoadFirstBatch();
    }

    /** 过滤并加载第一批 */
    private void applyFilterAndLoadFirstBatch() {
        filteredData.clear();
        if (filterKeyword.isEmpty()) {
            filteredData.addAll(fullData);
        } else {
            for (MusicBean b : fullData) {
                String title = b.getTitle().toLowerCase();
                String artist = b.getArtist() != null ? b.getArtist().toLowerCase() : "";
                String album = b.getAlbum() != null ? b.getAlbum().toLowerCase() : "";
                if (title.contains(filterKeyword)
                        || artist.contains(filterKeyword)
                        || album.contains(filterKeyword)) {
                    filteredData.add(b);
                }
            }
        }

        // 加载第一批
        int loadCount = Math.min(BATCH_SIZE, filteredData.size());
        for (int i = 0; i < loadCount; i++) {
            data.add(filteredData.get(i));
        }
        loadedCount = loadCount;
        hasMore = loadedCount < filteredData.size();
        notifyDataSetChanged();
    }

    /** 滚动时加载更多(由 RecyclerView 滚动监听调用) */
    public void loadMore() {
        if (isLoading || !hasMore) {
            return;
        }
        isLoading = true;
        int start = loadedCount;
        int end = Math.min(loadedCount + BATCH_SIZE, filteredData.size());
        for (int i = start; i < end; i++) {
            data.add(filteredData.get(i));
        }
        int addedCount = end - start;
        loadedCount = end;
        hasMore = loadedCount < filteredData.size();
        if (addedCount > 0) {
            notifyItemRangeInserted(start, addedCount);
        }
        isLoading = false;
    }

    /** 检查是否需要加载更多(在滚动时调用) */
    public void checkLoadMore(int lastVisiblePosition) {
        if (hasMore && !isLoading && lastVisiblePosition >= data.size() - 10) {
            loadMore();
        }
    }

    /** 获取当前显示列表(供 MainActivity 播放用) */
    public List<MusicBean> getDisplayList() {
        return filteredData;
    }

    /** 获取过滤后的总数(含未加载的) */
    public int getTotalFilteredCount() {
        return filteredData.size();
    }

    public void setPlayingIndex(int index) {
        int old = playingIndex;
        playingIndex = index;
        if (old != index) {
            if (old >= 0 && old < data.size()) notifyItemChanged(old);
            if (index >= 0 && index < data.size()) notifyItemChanged(index);
        }
    }

    public void setOnItemClickListener(OnItemClickListener l) {
        this.listener = l;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_music, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        MusicBean bean = data.get(position);
        holder.tvTitle.setText(bean.getTitle());
        holder.tvArtist.setText(bean.getArtist() + " · " + MusicBean.formatDuration(bean.getDuration()));
        holder.tvIndex.setText(String.valueOf(position + 1));

        boolean playing = position == playingIndex;
        holder.itemView.setBackgroundColor(playing
                ? ContextCompat.getColor(context, R.color.playing_highlight)
                : ContextCompat.getColor(context, R.color.list_item_bg));
        holder.tvTitle.setTextColor(ContextCompat.getColor(context,
                playing ? R.color.playing_text : R.color.text_primary));
        holder.tvArtist.setTextColor(ContextCompat.getColor(context,
                playing ? R.color.playing_text_sub : R.color.text_secondary));

        int sourceColor = bean.isNetwork()
                ? ContextCompat.getColor(context, R.color.source_network)
                : ContextCompat.getColor(context, R.color.source_local);
        holder.vSource.setBackgroundColor(sourceColor);

        int coverSize = (int) context.getResources().getDimension(R.dimen.cover_size_list);
        CoverLoader.getInstance().load(bean, holder.ivCover, coverSize);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(holder.getAdapterPosition(), bean);
            }
        });

        // 检查是否需要加载更多
        checkLoadMore(position);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvIndex;
        ImageView ivCover;
        TextView tvTitle;
        TextView tvArtist;
        View vSource;

        VH(@NonNull View itemView) {
            super(itemView);
            tvIndex = itemView.findViewById(R.id.tv_index);
            ivCover = itemView.findViewById(R.id.iv_cover);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvArtist = itemView.findViewById(R.id.tv_artist);
            vSource = itemView.findViewById(R.id.v_source);
        }
    }
}
