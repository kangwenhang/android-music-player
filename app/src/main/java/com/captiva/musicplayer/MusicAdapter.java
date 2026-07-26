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
 * - 点击震动反馈
 */
public class MusicAdapter extends RecyclerView.Adapter<MusicAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(int position, MusicBean bean);
    }

    private final List<MusicBean> fullData = new ArrayList<>();  // 完整列表
    private final List<MusicBean> data = new ArrayList<>();       // 当前显示列表(可能被搜索过滤)
    private final Context context;
    private OnItemClickListener listener;
    private int playingIndex = -1;
    private String filterKeyword = "";

    public MusicAdapter(Context context) {
        this.context = context;
    }

    public void setData(List<MusicBean> list) {
        fullData.clear();
        if (list != null) {
            fullData.addAll(list);
        }
        applyFilter();
    }

    /** 搜索过滤 */
    public void filter(String keyword) {
        filterKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
        applyFilter();
    }

    private void applyFilter() {
        data.clear();
        if (filterKeyword.isEmpty()) {
            data.addAll(fullData);
        } else {
            for (MusicBean b : fullData) {
                String title = b.getTitle().toLowerCase();
                String artist = b.getArtist().toLowerCase();
                String album = b.getAlbum().toLowerCase();
                if (title.contains(filterKeyword)
                        || artist.contains(filterKeyword)
                        || album.contains(filterKeyword)) {
                    data.add(b);
                }
            }
        }
        notifyDataSetChanged();
    }

    /** 获取当前显示列表(供 MainActivity 播放用) */
    public List<MusicBean> getDisplayList() {
        return data;
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
        // 高亮当前播放项
        holder.itemView.setBackgroundColor(playing
                ? ContextCompat.getColor(context, R.color.playing_highlight)
                : ContextCompat.getColor(context, R.color.list_item_bg));
        holder.tvTitle.setTextColor(ContextCompat.getColor(context,
                playing ? R.color.playing_text : R.color.text_primary));
        holder.tvArtist.setTextColor(ContextCompat.getColor(context,
                playing ? R.color.playing_text_sub : R.color.text_secondary));

        // 来源标识颜色:网络=绿色,本地=灰色
        int sourceColor = bean.isNetwork()
                ? ContextCompat.getColor(context, R.color.source_network)
                : ContextCompat.getColor(context, R.color.source_local);
        holder.vSource.setBackgroundColor(sourceColor);

        // 加载封面(异步)
        int coverSize = (int) context.getResources().getDimension(R.dimen.cover_size_list);
        CoverLoader.getInstance().load(bean, holder.ivCover, coverSize);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(holder.getAdapterPosition(), bean);
            }
        });
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
