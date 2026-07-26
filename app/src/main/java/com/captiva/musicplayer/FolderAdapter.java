package com.captiva.musicplayer;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件夹列表适配器
 */
public class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(int position, FolderScanner.Folder folder);
    }

    private final List<FolderScanner.Folder> data = new ArrayList<>();
    private final Context context;
    private OnItemClickListener listener;

    public FolderAdapter(Context context) {
        this.context = context;
    }

    public void setData(List<FolderScanner.Folder> list) {
        data.clear();
        if (list != null) {
            data.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener l) {
        this.listener = l;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_folder, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        FolderScanner.Folder folder = data.get(position);
        holder.tvName.setText(folder.name);
        holder.tvInfo.setText(folder.count + " 首 · " + folder.path);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(holder.getAdapterPosition(), folder);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName;
        TextView tvInfo;

        VH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_folder_name);
            tvInfo = itemView.findViewById(R.id.tv_folder_info);
        }
    }
}