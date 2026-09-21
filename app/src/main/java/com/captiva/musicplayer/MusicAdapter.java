package com.captiva.musicplayer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.DiffUtil;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static final String TAG = "MusicAdapter";

    public interface OnItemClickListener {
        void onItemClick(int position, MusicBean bean);
    }

    /** 收藏按钮点击回调 */
    public interface OnFavoriteClickListener {
        void onFavoriteClick(MusicBean bean, boolean isNowFavorite);
    }

    /** 异步过滤完成回调(主线程):DiffUtil 增量刷新已提交,供外部刷新依赖过滤结果的 UI(如歌曲计数) */
    public interface OnFilterCompleteListener {
        void onFilterComplete();
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
    private OnFilterCompleteListener filterCompleteListener;
    private FavoriteManager favoriteManager;
    private int playingIndex = -1;
    private String filterKeyword = "";

    /** 当前已加载到第几条(分批加载,针对 filteredData) */
    private int loadedCount = 0;
    /** 是否还有更多数据可加载 */
    private boolean hasMore = false;
    /** 是否正在加载更多(防止重复触发) */
    private boolean isLoading = false;

    // ===== 异步过滤 / DiffUtil 增量刷新相关字段 =====
    // 过滤遍历 + Diff 计算放到后台单线程,避免主线程遍历几百上千首导致掉帧
    private final ExecutorService filterExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 过滤请求代际:每次新请求 +1,过期的异步结果直接丢弃,避免旧结果覆盖新结果 */
    private int filterGeneration = 0;
    /** 当前过滤模式:false=普通搜索过滤,true=仅收藏 */
    private boolean favoritesMode = false;
    /** 收藏过滤使用的 FavoriteManager(异步计算时需要) */
    private FavoriteManager pendingFm = null;
    /** 防抖窗口:相同签名的过滤请求在此窗口内合并,避免输入/滑动抖动引发主线程重复刷新 */
    private static final long FILTER_DEBOUNCE_MS = 120;
    private String lastFilterSignature = "";
    private long lastFilterSubmitTime = 0;

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
     * 使用 DiffUtil 增量刷新(只重绑变化行),替代 notifyDataSetChanged
     */
    public synchronized void setData(List<MusicBean> list) {
        // 标记代际,使任何在途的异步过滤结果失效,避免覆盖本次新数据
        filterGeneration++;
        // 重置防抖签名,避免重载后一次相同关键词过滤被误判为重复而跳过
        lastFilterSignature = "";
        // 重新载入完整列表时回到普通模式(历史行为:setData 不应用收藏过滤)
        favoritesMode = false;
        pendingFm = null;
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
        List<MusicBean> oldData = new ArrayList<>(data);
        FilterResult r = computeFilteredUnsafe(favoritesMode, pendingFm);
        data.clear();
        data.addAll(r.firstBatch);
        filteredData.clear();
        filteredData.addAll(r.filtered);
        loadedCount = r.loadCount;
        hasMore = loadedCount < filteredData.size();
        long t0 = System.currentTimeMillis();
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new FilterDiffCallback(oldData, r.firstBatch), false);
        diff.dispatchUpdatesTo(this);
        long elapsed = System.currentTimeMillis() - t0;
        Log.i(TAG, "[setData] fullData=" + fullData.size() + " filtered=" + r.filtered.size()
                + " loaded=" + loadedCount + " diff=" + elapsed + "ms");
        if (PerfLogger.isEnabled()) {
            PerfLogger.log("setData", "fullData=" + fullData.size() + " filtered=" + r.filtered.size()
                    + " loaded=" + loadedCount + " diff=" + elapsed + "ms");
        }
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

    /** 生成歌曲唯一标识(使用 MusicBean 缓存,避免重复文件系统 I/O) */
    private String getSongKey(MusicBean b) {
        return b.getCachedKey();
    }

    /** 搜索过滤(主线程入口,遍历/diff 在后台线程执行) */
    public void filter(String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        filterKeyword = kw; // 立即更新,保证 matchesFilter 一致性(后台线程会读取)
        // 防抖:相同签名且窗口内重复提交 → 跳过(典型场景:空关键词连续触发 / 输入抖动)
        String signature = "f:" + kw;
        long now = System.currentTimeMillis();
        if (signature.equals(lastFilterSignature) && (now - lastFilterSubmitTime) < FILTER_DEBOUNCE_MS) {
            Log.d(TAG, "[filter] 防抖跳过重复请求 keyword='" + kw + "'");
            return;
        }
        lastFilterSignature = signature;
        lastFilterSubmitTime = now;
        Log.i(TAG, "[filter] 提交异步过滤 keyword='" + kw + "' fullData=" + fullData.size());
        requestFilter(false, null);
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
        if (TextUtils.isEmpty(filterKeyword)) {
            return true;
        }
        // 使用缓存的小写值,避免每次过滤都对 810 首歌调用 toLowerCase()
        return b.getLowerTitle().contains(filterKeyword) || b.getLowerArtist().contains(filterKeyword);
    }

    /**
     * 只显示收藏的歌曲(主线程入口)
     * 同时应用当前搜索关键词过滤(如果有的话)
     * 遍历/diff 在后台线程执行,主线程只做增量 dispatch
     */
    public void filterFavorites(FavoriteManager fm) {
        String signature = "v:" + filterKeyword;
        long now = System.currentTimeMillis();
        if (signature.equals(lastFilterSignature) && (now - lastFilterSubmitTime) < FILTER_DEBOUNCE_MS) {
            Log.d(TAG, "[filterFavorites] 防抖跳过重复请求");
            return;
        }
        lastFilterSignature = signature;
        lastFilterSubmitTime = now;
        pendingFm = fm;
        requestFilter(true, fm);
    }

    /**
     * 提交一次过滤请求(异步):
     * 后台线程遍历 fullData 计算过滤结果并计算 Diff,主线程只做增量 dispatchUpdatesTo,
     * 避免 notifyDataSetChanged 触发全量重绑导致车机掉帧。
     */
    private void requestFilter(final boolean favMode, final FavoriteManager fm) {
        final int gen = ++filterGeneration;
        favoritesMode = favMode;
        if (fm != null) pendingFm = fm;
        final long t0 = System.currentTimeMillis();
        filterExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // 1. 后台线程:遍历 fullData 计算过滤结果(避免主线程遍历上千首)
                final List<MusicBean> oldData;
                final FilterResult r;
                synchronized (MusicAdapter.this) {
                    oldData = new ArrayList<>(data);
                    r = computeFilteredUnsafe(favMode, fm);
                }
                // 2. 后台线程:计算 Diff(数据量小,通常 <1ms)
                final DiffUtil.DiffResult diff =
                        DiffUtil.calculateDiff(new FilterDiffCallback(oldData, r.firstBatch), false);
                final long tCompute = System.currentTimeMillis() - t0;
                // 3. 主线程:提交增量更新
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != filterGeneration) {
                            // 已被更新的过滤请求取代,丢弃本次结果
                            return;
                        }
                        long t1 = System.currentTimeMillis();
                        synchronized (MusicAdapter.this) {
                            data.clear();
                            data.addAll(r.firstBatch);
                            filteredData.clear();
                            filteredData.addAll(r.filtered);
                            loadedCount = r.loadCount;
                            hasMore = loadedCount < filteredData.size();
                        }
                        diff.dispatchUpdatesTo(MusicAdapter.this);
                        // 过滤完成:通知外部刷新依赖过滤结果的 UI(如歌曲计数)。
                        // 此时 filteredData 已是最终态,updateCount() 读到的数字才正确,
                        // 避免"滤后计数滞后一帧"导致的统计数错误。
                        if (filterCompleteListener != null) {
                            filterCompleteListener.onFilterComplete();
                        }
                        long tSwap = System.currentTimeMillis() - t1;
                        long elapsed = System.currentTimeMillis() - t0;
                        Log.i(TAG, "[applyFilter-async] 遍历+diff=" + tCompute + "ms swap=" + tSwap + "ms"
                                + " fullData=" + fullData.size() + " filtered=" + r.filtered.size()
                                + " loaded=" + r.loadCount + " 总=" + elapsed + "ms");
                        if (PerfLogger.isEnabled()) {
                            PerfLogger.log("applyFilter", "遍历+diff=" + tCompute + "ms swap=" + tSwap + "ms"
                                    + " fullData=" + fullData.size() + " filtered=" + r.filtered.size() + " " + elapsed + "ms");
                        }
                    }
                });
            }
        });
    }

    /**
     * 在持有 this 锁的前提下计算过滤结果(不自带锁,调用方必须同步)。
     * 同时应用搜索关键词与(可选)收藏过滤。
     */
    private FilterResult computeFilteredUnsafe(boolean favMode, FavoriteManager fm) {
        FilterResult r = new FilterResult();
        r.filtered = new ArrayList<>();
        for (MusicBean b : fullData) {
            if (favMode) {
                if (fm != null && fm.isFavorite(b) && matchesFilter(b)) {
                    r.filtered.add(b);
                }
            } else {
                if (matchesFilter(b)) {
                    r.filtered.add(b);
                }
            }
        }
        r.loadCount = Math.min(BATCH_SIZE, r.filtered.size());
        r.firstBatch = new ArrayList<>(r.filtered.subList(0, r.loadCount));
        return r;
    }

    /** 过滤计算结果载体 */
    private static class FilterResult {
        List<MusicBean> filtered;   // 过滤后的完整列表
        List<MusicBean> firstBatch; // 第一批(分批加载)要显示的列表
        int loadCount;
    }

    /**
     * DiffUtil 回调:以 getCachedKey() 作为稳定身份。
     * 内容视为相同(只有增/删/移动会被处理,未变化行不被重绑,避免掉帧);
     * 播放高亮变化通过 setPlayingIndex → notifyItemChanged 单独刷新。
     */
    private static class FilterDiffCallback extends DiffUtil.Callback {
        private final List<MusicBean> oldList;
        private final List<MusicBean> newList;
        FilterDiffCallback(List<MusicBean> oldList, List<MusicBean> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }
        @Override
        public int getOldListSize() { return oldList.size(); }
        @Override
        public int getNewListSize() { return newList.size(); }
        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            MusicBean a = oldList.get(oldItemPosition);
            MusicBean b = newList.get(newItemPosition);
            if (a == null || b == null) return false;
            String ka = a.getCachedKey();
            String kb = b.getCachedKey();
            if (ka == null || kb == null) return false;
            return ka.equals(kb);
        }
        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            return areItemsTheSame(oldItemPosition, newItemPosition);
        }
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
            // 确保新位置已加载到 data(搜索/过滤后当前歌曲可能在分批加载范围外)
            if (index >= 0 && index < filteredData.size() && index >= data.size()) {
                ensureLoaded(index);
            }
            if (old >= 0 && old < data.size()) notifyItemChanged(old);
            if (index >= 0 && index < data.size()) notifyItemChanged(index);
        }
    }

    /**
     * 确保指定位置的数据已加载(分批加载机制下,远处位置可能尚未加载)
     * @return true 如果位置在 filteredData 范围内
     */
    public synchronized boolean ensureLoaded(int position) {
        long t0 = System.currentTimeMillis();
        int startLoaded = loadedCount;
        if (position < 0 || position >= filteredData.size()) {
            return false;
        }
        // 如果位置已超出当前加载范围,一次性补充加载所有需要的批次
        // 然后只通知一次(原来每批 notifyItemRangeInserted,目标在 3000 时触发 60 次通知)
        while (loadedCount <= position && hasMore) {
            int start = loadedCount;
            int end = Math.min(loadedCount + BATCH_SIZE, filteredData.size());
            for (int i = start; i < end; i++) {
                data.add(filteredData.get(i));
            }
            loadedCount = end;
            hasMore = loadedCount < filteredData.size();
        }
        int added = loadedCount - startLoaded;
        if (added > 0) {
            // 只通知一次,而不是每批通知
            notifyItemRangeInserted(startLoaded, added);
            long elapsed = System.currentTimeMillis() - t0;
            Log.i(TAG, "[ensureLoaded] pos=" + position + " 加载" + added + "条"
                    + " loadedCount=" + loadedCount + "/" + filteredData.size() + " " + elapsed + "ms");
            if (PerfLogger.isEnabled()) {
                PerfLogger.log("ensureLoaded", "pos=" + position + " 加载" + added + "条 " + elapsed + "ms");
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
        long t0 = System.currentTimeMillis();
        if (target == null) return -1;
        String targetKey = getSongKey(target);
        if (targetKey == null) return -1;
        for (int i = 0; i < filteredData.size(); i++) {
            if (targetKey.equals(getSongKey(filteredData.get(i)))) {
                long elapsed = System.currentTimeMillis() - t0;
                Log.i(TAG, "[findPosition] 找到 pos=" + i + " 遍历" + (i + 1) + "/" + filteredData.size() + " " + elapsed + "ms");
                if (PerfLogger.isEnabled()) {
                    PerfLogger.log("findPosition", "pos=" + i + " 遍历" + (i + 1) + "/" + filteredData.size() + " " + elapsed + "ms");
                }
                return i;
            }
        }
        long elapsed = System.currentTimeMillis() - t0;
        Log.i(TAG, "[findPosition] 未找到 遍历" + filteredData.size() + "条 " + elapsed + "ms");
        if (PerfLogger.isEnabled()) {
            PerfLogger.log("findPosition", "未找到 遍历" + filteredData.size() + "条 " + elapsed + "ms");
        }
        return -1;
    }

    /** 获取 filteredData 中指定位置的歌曲(供外部查询) */
    public synchronized MusicBean getFilteredItem(int position) {
        if (position < 0 || position >= filteredData.size()) return null;
        return filteredData.get(position);
    }

    /**
     * 刷新指定范围内 item 的封面(不触发 onBindViewHolder,直接查找 ImageView)
     * 停止滑动后调用,避免 notifyItemRangeChanged 导致全部可见项重新绑定(46ms 尖峰)
     * @param rv RecyclerView(用于查找持有者)
     * @param start 起始位置
     * @param end 结束位置(含)
     * @param coverSize 封面尺寸
     */
    public void refreshCovers(RecyclerView rv, int start, int end, int coverSize) {
        if (rv == null) return;
        for (int i = start; i <= end; i++) {
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(i);
            if (vh instanceof VH) {
                VH holder = (VH) vh;
                if (i >= 0 && i < data.size()) {
                    MusicBean bean = data.get(i);
                    CoverLoader.getInstance().load(bean, holder.ivCover, coverSize);
                }
            }
        }
    }

    public void setOnItemClickListener(OnItemClickListener l) {
        this.listener = l;
    }

    public void setOnFavoriteClickListener(OnFavoriteClickListener l) {
        this.favoriteListener = l;
    }

    /** 注册异步过滤完成回调(主线程),用于在 DiffUtil 刷新后刷新计数等依赖过滤结果的 UI */
    public void setOnFilterCompleteListener(OnFilterCompleteListener l) {
        this.filterCompleteListener = l;
    }

    public void setFavoriteManager(FavoriteManager fm) {
        this.favoriteManager = fm;
    }

    /** 收藏状态变化后刷新列表显示(增量 diff:仅收藏模式会增删行) */
    public void notifyFavoriteChanged() {
        requestFilter(favoritesMode, pendingFm);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_music, parent, false);
        return new VH(v, this);
    }

    /** 复用的 StringBuilder(避免每次 onBind 创建新 String 对象,减少 GC) */
    private final StringBuilder bindBuffer = new StringBuilder(64);

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        long t0 = PerfLogger.isEnabled() ? System.currentTimeMillis() : 0;
        // 安全检查:防止 position 越界
        if (position < 0 || position >= data.size()) {
            return;
        }
        MusicBean bean = data.get(position);
        holder.tvTitle.setText(bean.getTitle());

        // 复用 StringBuilder 拼接副标题(避免 String + 创建临时对象)
        bindBuffer.setLength(0);
        bindBuffer.append(bean.getArtist())
                  .append(" · ")
                  .append(MusicBean.formatDuration(bean.getDuration()));
        holder.tvArtist.setText(bindBuffer);
        holder.tvIndex.setText(String.valueOf(position + 1));

        // 使用缓存的颜色(避免每次 bind 调用 ContextCompat.getColor)
        boolean playing = position == playingIndex;
        holder.itemView.setBackgroundColor(playing ? colorPlayingBg : colorListItemBg);
        holder.tvTitle.setTextColor(playing ? colorPlayingText : colorTextPrimary);
        holder.tvArtist.setTextColor(playing ? colorPlayingTextSub : colorTextSecondary);
        holder.vSource.setBackgroundColor(bean.isNetwork() ? colorSourceNetwork : colorSourceLocal);

        // 使用缓存的封面尺寸
        CoverLoader.getInstance().load(bean, holder.ivCover, coverSizeList);

        // 点击监听器在 VH 构造时设置,这里不需要重复创建(减少 GC)

        // 检查是否需要加载更多(用 holder 的 post,Runnable 在 VH 中复用)
        holder.postCheckLoadMore();

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
        MusicAdapter adapter;

        VH(@NonNull View itemView, MusicAdapter adapter) {
            super(itemView);
            this.adapter = adapter;
            tvIndex = itemView.findViewById(R.id.tv_index);
            ivCover = itemView.findViewById(R.id.iv_cover);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvArtist = itemView.findViewById(R.id.tv_artist);
            vSource = itemView.findViewById(R.id.v_source);

            // 点击监听器只创建一次(避免每次 onBindViewHolder 创建新 lambda → GC)
            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (adapter.listener != null) {
                        int pos = getAdapterPosition();
                        if (pos >= 0 && pos < adapter.data.size()) {
                            adapter.listener.onItemClick(pos, adapter.data.get(pos));
                        }
                    }
                }
            });
        }

        /** 复用的 checkLoadMore Runnable(避免每次 post 创建新对象 → GC) */
        private final Runnable loadMoreRunnable = new Runnable() {
            @Override
            public void run() {
                int pos = getAdapterPosition();
                if (pos >= 0) {
                    adapter.checkLoadMore(pos);
                }
            }
        };

        void postCheckLoadMore() {
            itemView.post(loadMoreRunnable);
        }
    }
}
