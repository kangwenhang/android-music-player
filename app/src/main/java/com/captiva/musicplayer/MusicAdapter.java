package com.captiva.musicplayer;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌曲列表适配器
 */
public class MusicAdapter extends RecyclerView.Adapter<MusicAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(int position, MusicBean bean);
    }

    private final List<MusicBean> data = new ArrayList<>();
    private final Context context;
    private OnItemClickListener listener;
    private int playingIndex = -1;

    public MusicAdapter(Context context) {
        this.context = context;
    }

    public void setData(List<MusicBean> list) {
        data.clear();
        if (list != null) {
            data.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void setPlayingIndex(int index) {
        int old = playingIndex;
        playingIndex = index;
        if (old != index) {
            notifyItemChanged(old);
            notifyItemChanged(index);
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
        TextView tvTitle;
        TextView tvArtist;

        VH(@NonNull View itemView) {
            super(itemView);
            tvIndex = itemView.findViewById(R.id.tv_index);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvArtist = itemView.findViewById(R.id.tv_artist);
        }
    }
}