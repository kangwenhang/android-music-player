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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 歌曲列表适配器
 * - 封面图异步加载
 * - 本地/网络来源标识
 * - 搜索过滤
 * - 分批加载(防止大量数据卡死车机)
 *
 * 线程安全说明:
 * - fullData/filteredData/data 三个列表只在主线程修改
 * - appendData() 由后台线程调用,内部通过 runOnUiThread 或同步块保证安全
 * - 所有 notifyXXX 在主线程执行
 */
public class MusicAdapter extends RecyclerView.Adapter<MusicAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(int position, MusicBean bean);
    }

    /** 收藏按钮点击回调 */
    public interface OnFavoriteClickListener {
        void onFavoriteClick(MusicBean bean, boolean isNowFavorite);
    }

    /** 滚动加载每批数量(车机性能弱,小批量) */
    private static final int BATCH_SIZE = 50;

    private final List<MusicBean> fullData = new ArrayList<>();  // 完整列表
    private final Set<String> fullDataKeys = new HashSet<>();    // fullData 的 key 集合(O(1)去重)
    private final List<MusicBean> filteredData = new ArrayList<>(); // 过滤后的完整列表
    private final List<MusicBean> data = new ArrayList<>();       // 当前显示列表(分批加载)
    private final Context context;
    private OnItemClickListener listener;
    private OnFavoriteClickListener favoriteListener;
    private FavoriteManager favoriteManager;
    private int playingIndex = -1;
    private String filterKeyword = "";

    /** 当前已加载到第几条(分批加载,针对 filteredData) */
    private int loadedCount = 0;
    /** 是否还有更多数据可加载 */
    private boolean hasMore = false;
    /** 是否正在加载更多(防止重复触发) */
    private boolean isLoading = false;

    // 缓存颜色和尺寸(避免每次 onBindViewHolder 重复查询 Resources)
    private final int colorPlayingBg;
    private final int colorListItemBg;
    private final int colorPlayingText;
    private final int colorPlayingTextSub;
    private final int colorTextPrimary;
    private final int colorTextSecondary;
    private final int colorSourceNetwork;
    private final int colorSourceLocal;
    private final int coverSizeList;
    private final int colorFavoriteActive;
    private final int colorFavoriteInactive;

    public MusicAdapter(Context context) {
        this.context = context;
        // 预加载所有颜色和尺寸(只执行一次)
        colorPlayingBg = ContextCompat.getColor(context, R.color.playing_highlight);
        colorListItemBg = ContextCompat.getColor(context, R.color.list_item_bg);
        colorPlayingText = ContextCompat.getColor(context, R.color.playing_text);
        colorPlayingTextSub = ContextCompat.getColor(context, R.color.playing_text_sub);
        colorTextPrimary = ContextCompat.getColor(context, R.color.text_primary);
        colorTextSecondary = ContextCompat.getColor(context, R.color.text_secondary);
        colorSourceNetwork = ContextCompat.getColor(context, R.color.source_network);
        colorSourceLocal = ContextCompat.getColor(context, R.color.source_local);
        coverSizeList = (int) context.getResources().getDimension(R.dimen.cover_size_list);
        colorFavoriteActive = ContextCompat.getColor(context, R.color.favorite_active);
        colorFavoriteInactive = ContextCompat.getColor(context, R.color.favorite_inactive);
        // 启用稳定 ID 提升 RecyclerView 回收效率
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= data.size()) return RecyclerView.NO_ID;
        MusicBean bean = data.get(position);
        String key = getSongKey(bean);
        return key != null ? key.hashCode() : RecyclerView.NO_ID;
    }

    /**
     * 设置完整数据(主线程调用)
     * 替换 fullData,重建 filteredData,加载第一批到 data
     */
    public synchronized void setData(List<MusicBean> list) {
        fullData.clear();
        fullDataKeys.clear();
        if (list != null) {
            for (MusicBean b : list) {
                String key = getSongKey(b);
                if (key != null && !fullDataKeys.contains(key)) {
                    fullData.add(b);
                    fullDataKeys.add(key);
                }
            }
        }
        loadedCount = 0;
        data.clear();
        applyFilterAndLoadFirstBatch();
    }

    /**
     * 追加数据(后台分页加载完成后调用)
     * 注意:此方法可能在后台线程调用,需保证线程安全
     */
    public synchronized void appendData(List<MusicBean> more) {
        if (more == null || more.isEmpty()) {
            return;
        }
        // 1. 用 HashSet O(1) 去重,只收集真正新增的歌曲
        List<MusicBean> newlyAdded = new ArrayList<>();
        for (MusicBean b : more) {
            String key = getSongKey(b);
            if (key != null && !fullDataKeys.contains(key)) {
                fullData.add(b);
                fullDataKeys.add(key);
                newlyAdded.add(b);
            }
        }
        if (newlyAdded.isEmpty()) {
            return;
        }

        // 2. 如果没有搜索过滤,把新增的加入 filteredData
        if (filterKeyword.isEmpty()) {
            for (MusicBean b : newlyAdded) {
                filteredData.add(b);
            }
            // 3. 检查是否需要把部分新数据加载到 data(显示列表)
            int oldDataSize = data.size();
            if (loadedCount < filteredData.size()) {
                int canAdd = Math.min(BATCH_SIZE, filteredData.size() - loadedCount);
                for (int i = 0; i < canAdd; i++) {
                    if (loadedCount < filteredData.size()) {
                        data.add(filteredData.get(loadedCount));
                        loadedCount++;
                    }
                }
                int newAdded = data.size() - oldDataSize;
                if (newAdded > 0) {
                    notifyItemRangeInserted(oldDataSize, newAdded);
                }
            }
            hasMore = loadedCount < filteredData.size();
        }
        // 如果有搜索过滤,filteredData会在下次filter时重建
    }

    /** 生成歌曲唯一标识(网络用 streamId,本地用规范化文件路径) */
    private String getSongKey(MusicBean b) {
        if (b.isNetwork()) {
            return "net_" + b.getStreamId();
        } else {
            String data = b.getData();
            if (data != null && !data.isEmpty()) {
                return "local_" + MusicScanner.normalizePath(data);
            }
            return "local_" + b.getId();
        }
    }

    /** 搜索过滤(主线程) */
    public synchronized void filter(String keyword) {
        filterKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
        loadedCount = 0;
        data.clear();
        applyFilterAndLoadFirstBatch();
    }

    /**
     * 设置搜索关键词(不立即过滤)
     * 用于 filterFavorites 前设置关键词,使收藏过滤也应用搜索
     */
    public void setSearchKeyword(String keyword) {
        filterKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
    }

    /**
     * 判断歌曲是否匹配当前搜索关键词
     * 只按歌名和歌手匹配,不搜专辑名(避免误匹配)
     */
    private boolean matchesFilter(MusicBean b) {
        if (filterKeyword.isEmpty()) {
            return true;
        }
        String title = b.getTitle().toLowerCase();
        String artist = b.getArtist() != null ? b.getArtist().toLowerCase() : "";
        return title.contains(filterKeyword) || artist.contains(filterKeyword);
    }

    /**
     * 只显示收藏的歌曲(主线程)
     * 同时应用当前搜索关键词过滤(如果有的话)
     */
    public synchronized void filterFavorites(FavoriteManager fm) {
        loadedCount = 0;
        data.clear();
        filteredData.clear();
        if (fm != null) {
            for (MusicBean b : fullData) {
                if (fm.isFavorite(b) && matchesFilter(b)) {
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

    /** 过滤并加载第一批(主线程) */
    private void applyFilterAndLoadFirstBatch() {
        filteredData.clear();
        for (MusicBean b : fullData) {
            if (matchesFilter(b)) {
                filteredData.add(b);
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

    /** 滚动时加载更多(由 onBindViewHolder 调用,主线程) */
    public synchronized void loadMore() {
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
    public synchronized void checkLoadMore(int lastVisiblePosition) {
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

    /** 获取全量歌曲总数(不受搜索/收藏过滤影响) */
    public int getTotalCount() {
        return fullData.size();
    }

    public void setPlayingIndex(int index) {
        int old = playingIndex;
        playingIndex = index;
        if (old != index) {
            if (old >= 0 && old < data.size()) notifyItemChanged(old);
            if (index >= 0 && index < data.size()) notifyItemChanged(index);
        }
    }

    /**
     * 确保指定位置的数据已加载(分批加载机制下,远处位置可能尚未加载)
     * @return true 如果位置在 filteredData 范围内
     */
    public synchronized boolean ensureLoaded(int position) {
        if (position < 0 || position >= filteredData.size()) {
            return false;
        }
        // 如果位置已超出当前加载范围,补充加载
        while (loadedCount <= position && hasMore) {
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
        }
        return position < data.size();
    }

    /**
     * 根据歌曲对象在 filteredData 中查找位置(用 song key 匹配)
     * 用于播放列表和显示列表不一致时,定位当前播放歌曲
     * @return 位置索引,未找到返回 -1
     */
    public synchronized int findPositionByBean(MusicBean target) {
        if (target == null) return -1;
        String targetKey = getSongKey(target);
        if (targetKey == null) return -1;
        for (int i = 0; i < filteredData.size(); i++) {
            if (targetKey.equals(getSongKey(filteredData.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    /** 获取 filteredData 中指定位置的歌曲(供外部查询) */
    public synchronized MusicBean getFilteredItem(int position) {
        if (position < 0 || position >= filteredData.size()) return null;
        return filteredData.get(position);
    }

    public void setOnItemClickListener(OnItemClickListener l) {
        this.listener = l;
    }

    public void setOnFavoriteClickListener(OnFavoriteClickListener l) {
        this.favoriteListener = l;
    }

    public void setFavoriteManager(FavoriteManager fm) {
        this.favoriteManager = fm;
    }

    /** 收藏状态变化后刷新列表显示 */
    public void notifyFavoriteChanged() {
        notifyDataSetChanged();
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
        long t0 = PerfLogger.isEnabled() ? System.currentTimeMillis() : 0;
        // 安全检查:防止 position 越界
        if (position < 0 || position >= data.size()) {
            return;
        }
        MusicBean bean = data.get(position);
        holder.tvTitle.setText(bean.getTitle());
        holder.tvArtist.setText(bean.getArtist() + " · " + MusicBean.formatDuration(bean.getDuration()));
        holder.tvIndex.setText(String.valueOf(position + 1));

        // 使用缓存的颜色(避免每次 bind 调用 ContextCompat.getColor)
        boolean playing = position == playingIndex;
        holder.itemView.setBackgroundColor(playing ? colorPlayingBg : colorListItemBg);
        holder.tvTitle.setTextColor(playing ? colorPlayingText : colorTextPrimary);
        holder.tvArtist.setTextColor(playing ? colorPlayingTextSub : colorTextSecondary);
        holder.vSource.setBackgroundColor(bean.isNetwork() ? colorSourceNetwork : colorSourceLocal);

        // 使用缓存的封面尺寸
        CoverLoader.getInstance().load(bean, holder.ivCover, coverSizeList);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                int pos = holder.getAdapterPosition();
                if (pos >= 0 && pos < data.size()) {
                    listener.onItemClick(pos, data.get(pos));
                }
            }
        });

        // 检查是否需要加载更多(用 post 延迟执行,避免在 onBindViewHolder 布局计算时调用 notifyItemRangeInserted 抛出 IllegalStateException)
        holder.itemView.post(() -> checkLoadMore(position));

        if (PerfLogger.isEnabled()) {
            PerfLogger.log("onBind", System.currentTimeMillis() - t0);
        }
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
