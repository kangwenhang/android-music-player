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
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.DiffUtil;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** 是否显示来源状态点(本地模式下全部是本地歌曲,点无信息量 → 隐藏) */
    private boolean showSourceDot = true;
    /** 正在按需缓存的歌曲进度表:key=streamId,value=百分比(0-100;-1=总长未知,不定进度) */
    private final Map<String, Integer> cacheProgress = new HashMap<>();

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
    /**
     * 过滤请求代际:每次提交新过滤请求 +1,过期的异步结果直接丢弃,避免旧结果覆盖新结果。
     *
     * 注意:这个计数器**只能**由 requestFilter() 推进,代表"有没有更新的过滤请求"。
     * 千万不要拿它去表示"data 被结构性改动了" —— 那会把本次过滤结果整包作废,
     * 列表就永远不刷新(表现为"点了退出收藏夹/搜索,列表纹丝不动")。
     * data 的结构性改动请走 {@link #dataVersion}。
     */
    private volatile int filterGeneration = 0;
    /**
     * data 列表的结构性改动代际:分批补加载(ensureLoaded)、追加(appendData)、
     * 整体替换(setData)时 +1。
     *
     * 它和 filterGeneration 的区别:过滤结果 r.filtered **依然有效**(全量数据没换),
     * 只是当初按旧快照算出来的 Diff 不适用了 —— 这种情况应该**重算一次 Diff 再套用**,
     * 而不是把结果丢掉。以前两者共用 filterGeneration,于是滚动补批一次
     * (退出收藏夹后 post 的高亮就会触发 ensureLoaded)就把"恢复全部歌曲"的结果吃掉了。
     */
    private volatile int dataVersion = 0;
    /** 当前过滤模式:false=普通搜索过滤,true=仅收藏 */
    private boolean favoritesMode = false;
    /** 收藏过滤使用的 FavoriteManager(异步计算时需要) */
    private FavoriteManager pendingFm = null;
    /**
     * 云端收藏夹使用的**服务器收藏 streamId 集合**;null 表示用本地 FavoriteManager 过滤。
     *
     * 两种收藏来源分开处理:云端模式看服务器 starred(跨设备同步),
     * 本地模式看本机 FavoriteManager(离线可用)。两者互斥,靠这个字段是否为 null 区分。
     */
    private volatile Set<String> cloudStarredIds = null;
    /**
     * 收藏集合的代次:每增删一个云端收藏 ID 就 +1。
     * 存在的理由:防抖签名原本只有 "关键词",而"收藏状态变了但关键词没变"时
     * 两次 filterFavorites 的签名完全相同 —— 一旦落在防抖窗口内,列表就会**不刷新**
     * (表现为"点了收藏列表没反应")。把它并进签名,收藏变化就一定不会被防抖吃掉。
     */
    private volatile int favVersion = 0;
    /** 防抖窗口:相同签名的过滤请求在此窗口内合并,避免输入/滑动抖动引发主线程重复刷新 */
    private static final long FILTER_DEBOUNCE_MS = 120;
    private String lastFilterSignature = "";
    private long lastFilterSubmitTime = 0;
    /**
     * 下一次过滤完成后是否记一行结果日志。
     * 只对 force 提交(模式切换这类关键操作)打开 —— 搜索输入每敲一个字都会过滤,
     * 全记会把 download_debug.log 刷爆。
     */
    private volatile boolean logNextFilter = false;

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
        // 整体替换数据:在途异步过滤是按**旧 fullData** 算的,结果本身已无意义,
        // 所以这里要连过滤代次一起作废(不只是 dataVersion)。
        filterGeneration++;
        dataVersion++;
        // 重置防抖签名,避免重载后一次相同关键词过滤被误判为重复而跳过
        lastFilterSignature = "";
        // 收藏模式要**保留**:原来这里无条件 favoritesMode=false,于是收藏夹模式下
        // 任何一次后台刷新(setData:扫描合并 / 同步完成 / 切歌后补全)都会把列表
        // 悄悄打回"全部歌曲",而按钮仍显示收藏态 —— 表现就是"点了不过滤"。
        // 现在非收藏模式下 favoritesMode 本来就 false,行为完全不变;
        // 收藏模式下沿用 pendingFm 重新计算,刷新后依然是收藏列表。
        if (!favoritesMode) {
            pendingFm = null;
        }
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
        FilterResult r = computeFilteredUnsafe(favoritesMode, pendingFm, cloudStarredIds);
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
        // 追加了新歌:在途异步过滤漏算了这批新数据,结果不完整,同样整体作废
        filterGeneration++;
        dataVersion++;
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

    /** 生成歌曲唯一标识(跨列表身份键:同一首歌云端/本地同键,收藏与高亮跨列表一致) */
    private String getSongKey(MusicBean b) {
        return b.getIdentityKey();
    }

    /** 搜索过滤(主线程入口,遍历/diff 在后台线程执行) */
    public void filter(String keyword) {
        filter(keyword, false);
    }

    /**
     * 普通过滤(可选强制)。
     *
     * force=true 用于**模式切换**这种必须生效的关键操作(退出收藏夹等):
     * 防抖本来是给"输入抖动/空关键词连环触发"用的,但模式切换时关键词往往没变,
     * 一旦签名撞上就被静默跳过 —— 表现就是"点了退出收藏夹,列表没变"。
     * 这类操作不该由防抖决定成败。
     */
    public void filter(String keyword, boolean force) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        filterKeyword = kw; // 立即更新,保证 matchesFilter 一致性(后台线程会读取)
        // 防抖:相同签名且窗口内重复提交 → 跳过(典型场景:空关键词连续触发 / 输入抖动)
        String signature = "f:" + kw;
        long now = System.currentTimeMillis();
        if (!force && signature.equals(lastFilterSignature)
                && (now - lastFilterSubmitTime) < FILTER_DEBOUNCE_MS) {
            Log.d(TAG, "[filter] 防抖跳过重复请求 keyword='" + kw + "'");
            // 车机看不到 logcat,被跳过这种事必须落到文件里 —— 它正是"点了没反应"的头号嫌疑
            DownloadDiag.log("[列表] 普通过滤被防抖跳过 keyword='" + kw
                    + "'(距今 " + (now - lastFilterSubmitTime) + "ms < " + FILTER_DEBOUNCE_MS + "ms)");
            return;
        }
        lastFilterSignature = signature;
        lastFilterSubmitTime = now;
        logNextFilter = force;   // 关键操作记一行结果,搜索输入不记(避免刷屏)
        Log.i(TAG, "[filter] 提交异步过滤 keyword='" + kw + "' fullData=" + fullData.size()
                + " force=" + force);
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
        filterFavorites(fm, false);
    }

    /** 收藏过滤(可选强制);force 的用途同 {@link #filter(String, boolean)} */
    public void filterFavorites(FavoriteManager fm, boolean force) {
        // 签名里带上收藏代次:收藏增删后即使关键词没变,也必须重新过滤
        String signature = "v:" + filterKeyword + ":" + favVersion;
        long now = System.currentTimeMillis();
        if (!force && signature.equals(lastFilterSignature)
                && (now - lastFilterSubmitTime) < FILTER_DEBOUNCE_MS) {
            Log.d(TAG, "[filterFavorites] 防抖跳过重复请求");
            DownloadDiag.log("[列表] 收藏过滤被防抖跳过(距今 "
                    + (now - lastFilterSubmitTime) + "ms < " + FILTER_DEBOUNCE_MS + "ms)");
            return;
        }
        lastFilterSignature = signature;
        lastFilterSubmitTime = now;
        logNextFilter = force;
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
        // 提交时快照"data 结构版本":dispatch 前若发现变了,说明期间有人补加载/追加过,
        // 旧 Diff 的基线不再是当前 data,必须重算(见下方 dispatch)。
        final int dataVer = dataVersion;
        // 结果日志开关:只有 force 提交(模式切换)才记,取到局部变量后立即复位
        final boolean needLog = logNextFilter;
        logNextFilter = false;
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
                    r = computeFilteredUnsafe(favMode, fm, cloudStarredIds);
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
                            // 已被**更新的过滤请求**取代,丢弃本次结果。
                            // 车机看不到 logcat,这种"算了但没用上"必须落到文件里,
                            // 否则排查时只能靠猜。
                            DownloadDiag.log("[列表] 过滤结果作废:已被更新的过滤请求取代"
                                    + " (本次=" + gen + " 当前=" + filterGeneration + ")");
                            return;
                        }
                        long t1 = System.currentTimeMillis();
                        DiffUtil.DiffResult diffToApply = diff;
                        boolean reDiff = false;
                        if (dataVer != dataVersion) {
                            // 期间 data 被结构性地补加载过(滚动/高亮触发 ensureLoaded):
                            // 过滤结果 r.filtered 仍然是对的,只是当初的 Diff 基线
                            // (提交时的 data 快照)已经不是现在屏幕上这一份。
                            // 用当前 data 重算一次 Diff 再套用 —— 以前这里是直接丢弃,
                            // 于是"退出收藏夹"的恢复结果被吃掉,列表一直显示收藏。
                            reDiff = true;
                            List<MusicBean> curData;
                            synchronized (MusicAdapter.this) {
                                curData = new ArrayList<>(data);
                            }
                            // Diff 是 O(n·d),放到锁外算:别在主线程持锁期间做这种遍历,
                            // 后台过滤线程正拿着同一把锁遍历 fullData,会互相阻塞。
                            diffToApply = DiffUtil.calculateDiff(
                                    new FilterDiffCallback(curData, r.firstBatch), false);
                        }
                        synchronized (MusicAdapter.this) {
                            data.clear();
                            data.addAll(r.firstBatch);
                            filteredData.clear();
                            filteredData.addAll(r.filtered);
                            loadedCount = r.loadCount;
                            hasMore = loadedCount < filteredData.size();
                        }
                        diffToApply.dispatchUpdatesTo(MusicAdapter.this);
                        if (needLog) {
                            DownloadDiag.log("[列表] 过滤完成 模式=" + (favMode ? "仅收藏" : "全部")
                                    + " 关键词='" + filterKeyword + "' 显示=" + r.filtered.size()
                                    + "/" + fullData.size() + " 首 (重算Diff=" + reDiff + ")");
                        }
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
    private FilterResult computeFilteredUnsafe(boolean favMode, FavoriteManager fm,
                                                Set<String> cloudIds) {
        FilterResult r = new FilterResult();
        r.filtered = new ArrayList<>();
        for (MusicBean b : fullData) {
            if (favMode) {
                if (cloudIds != null) {
                    // 云端收藏夹:按服务器返回的 streamId 集合过滤,与本地收藏无关
                    String sid = b.getStreamId();
                    if (sid != null && cloudIds.contains(sid) && matchesFilter(b)) {
                        r.filtered.add(b);
                    }
                } else if (fm != null && fm.isFavorite(b) && matchesFilter(b)) {
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
     * DiffUtil 回调:以 getIdentityKey() 作为稳定身份(跨云端/本地列表同键,
     * 切换列表时同一首歌被视为同一行,平滑过渡而不是整表重绑)。
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
            String ka = a.getIdentityKey();
            String kb = b.getIdentityKey();
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

    /** 设置是否显示来源状态点(本地模式=false 隐藏;须主线程调用) */
    public void setShowSourceDot(boolean show) {
        setShowSourceDot(show, true);
    }

    /**
     * 设置是否显示来源状态点。
     *
     * @param notify false = 只改状态、不通知刷新。
     *   给「来源切换」用:切换后马上就会 setData 整表换新数据,新数据自然带着
     *   最新的 showSourceDot 渲染出来;此时若还调 notifyDataSetChanged(),
     *   会在下一帧白白重绑一次全部可见行(车机上实测 ~180ms),
     *   把「点完按钮 → 加载态上屏」这段硬生生拖长。
     */
    public void setShowSourceDot(boolean show, boolean notify) {
        if (showSourceDot == show) return;
        showSourceDot = show;
        if (notify) notifyDataSetChanged();
    }

    /**
     * 更新某首歌的按需缓存进度(主线程调用,由缓存进度广播驱动)。
     * @param percent 0-100;-1=总长未知(不定进度);&lt;0 的其他值表示下载已结束,隐藏进度条
     */
    public void updateCacheProgress(String streamId, int percent) {
        if (streamId == null || streamId.isEmpty()) return;
        Integer old = cacheProgress.get(streamId);
        if (percent < 0 && percent != -1) {
            // 下载结束:移除进度并刷新对应行(恢复普通状态)
            if (old != null) {
                cacheProgress.remove(streamId);
                notifyRowProgress(streamId);
            }
            return;
        }
        if (old != null && old.equals(percent)) return;
        cacheProgress.put(streamId, percent);
        notifyRowProgress(streamId);
    }

    /**
     * 只刷新进度条那一格(局部刷新)。
     * 注意与 {@link #refreshRowByStreamId} 的区别:后者是"来源标识 云端→本地"
     * 这种整行状态变化(下载完成才一次),必须整行重绑。
     */
    private void notifyRowProgress(String streamId) {
        int pos = findRowByStreamId(streamId);
        if (pos >= 0) {
            notifyItemChanged(pos, PAYLOAD_CACHE_PROGRESS);
        }
    }

    /** 清除某首歌的缓存进度显示(下载完成转本地后调用) */
    public void clearCacheProgress(String streamId) {
        if (streamId == null || streamId.isEmpty()) return;
        if (cacheProgress.remove(streamId) != null) {
            notifyRowByStreamId(streamId);
        }
    }

    /** 按 streamId 单独刷新一行(供"云端→本地"来源标识变化时调用) */
    public void refreshRowByStreamId(String streamId) {
        if (streamId == null || streamId.isEmpty()) {
            return;
        }
        notifyRowByStreamId(streamId);
    }

    /** 按 streamId 找到可见列表中的行并单独刷新(避免 notifyDataSetChanged 全表重绑) */
    private void notifyRowByStreamId(String streamId) {
        int pos = findRowByStreamId(streamId);
        if (pos >= 0) {
            notifyItemChanged(pos);
            return;
        }
        // 诊断:可见列表中找不到对应行(如该歌尚未加载到当前批次)→ 进度条不会显示
        CacheDebugLog.log("进度刷新未命中可见行 streamId=" + streamId
                + " data=" + data.size() + " filtered=" + filteredData.size());
    }

    /**
     * 找 streamId 对应的行号。
     *
     * 为什么要有"上次命中"缓存:这是**每次下载进度广播**都要走的路径 ——
     * 一首歌下载期间会连发几十次,而 data 现在最多是**全量 810 首**
     * ("退出收藏夹恢复全部歌曲"修好之后才变成这样;以前收藏夹里只有 61 首,
     *  从头扫一遍几乎无感)。每次广播都线性扫 810 行再叠加一次重绑,
     * 滚动时就是明显的掉帧。而连续广播针对的**总是同一首歌、同一位置**,
     * 所以命中上次结果即可直接复用。
     *
     * 安全性:缓存只作为"快速路径",每次都会校验下标未越界且该行的 streamId 确实匹配;
     * 列表被替换/重排后必然不匹配,自然退回全扫描。
     */
    private int findRowByStreamId(String streamId) {
        if (streamId.equals(lastRowSid) && lastRowPos >= 0 && lastRowPos < data.size()) {
            MusicBean b = data.get(lastRowPos);
            if (b != null && streamId.equals(b.getStreamId())) {
                return lastRowPos;                 // 连续广播命中同一行,O(1)
            }
        }
        for (int i = 0; i < data.size(); i++) {
            MusicBean b = data.get(i);
            if (b != null && streamId.equals(b.getStreamId())) {
                lastRowSid = streamId;
                lastRowPos = i;
                return i;
            }
        }
        lastRowSid = streamId;   // 未命中也要记住,免得下次再白扫一遍
        lastRowPos = -1;
        return -1;
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
            // 确实往 data 里追加了行:结构性改动,推进 dataVersion —— 让在途过滤结果
            // 在 dispatch 前按当前 data **重算一次 Diff**,而不是被整包作废。
            //
            // 这里以前写的是 filterGeneration++ —— 那一次自增会在"退出收藏夹"时吃掉
            // 刚提交的"恢复全部歌曲"过滤结果:切换分支里 rvList.post(updatePlayingHighlight)
            // 会走到本方法,于是过滤结果到主线程时被判定为过期直接丢弃,列表纹丝不动,
            // 而顶部计数(同步调用)已经变成"共 810 首" —— 正是用户截图里的现象。
            // 过滤代次只代表"有没有更新的过滤请求",与本方法无关。
            dataVersion++;
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

    /**
     * 直接设置收藏过滤模式(不触发异步过滤)。
     * 给"云端收藏夹"用:它的列表本身就是服务器返回的收藏曲目,
     * 不需要再用本地 FavoriteManager 过滤一遍,必须先关掉这个模式,
     * 否则 setData 会拿本地收藏集合把服务器列表过滤掉(本地没收藏过就变空列表)。
     */
    public void setFavoritesMode(boolean on) {
        favoritesMode = on;
        if (!on) {
            pendingFm = null;
        }
    }

    /**
     * 设置云端收藏夹的服务器收藏 streamId 集合;
     * 传 null 表示切回"用本地 FavoriteManager 过滤"。
     */
    public synchronized void setCloudStarredIds(Set<String> ids) {
        cloudStarredIds = ids;
    }

    /** 当前是否处于云端收藏夹模式(列表由服务器收藏 ID 过滤而来) */
    public boolean isCloudFavoritesMode() {
        return cloudStarredIds != null;
    }

    /** 云端收藏夹里的收藏数量(用于空提示判断;非云端模式返回 -1) */
    public int getCloudStarredCount() {
        return cloudStarredIds == null ? -1 : cloudStarredIds.size();
    }

    /**
     * 云端收藏夹:**本地增量**地加/删一个 streamId,并立即重过滤(**不联网**)。
     *
     * 存在的理由:以前在收藏夹里点一下爱心,要等调用方把服务器收藏列表整个重拉一遍
     * (翻页 + 一次完整的网络往返)列表才会动 —— 用户看到的就是"点了半天没反应",
     * 而服务端慢/不可达时甚至永远不动。
     * 收藏与取消收藏是本地已知的确定操作,完全可以**乐观更新**:先改集合、立刻重过滤,
     * 服务器同步交给后台线程;只有同步失败才由调用方回滚(重新拉真实状态)。
     *
     * @param add true=加入收藏,false=取消收藏
     * @return 是否真的改动了(不在云端收藏夹模式 / id 为空 / 集合本来就是这个状态 → false)
     */
    public boolean updateCloudStarredId(String sid, boolean add) {
        if (cloudStarredIds == null || sid == null || sid.isEmpty()) {
            return false;
        }
        boolean changed;
        // 集合本身是可变 HashSet,而 computeFilteredUnsafe 会在后台线程遍历它
        // (调用处已 synchronized(this))—— 原地增删必须与它互斥,否则可能撞
        // ConcurrentModificationException。注意别把 requestFilter 放进锁里。
        synchronized (this) {
            changed = add ? cloudStarredIds.add(sid) : cloudStarredIds.remove(sid);
        }
        if (!changed) {
            return false;
        }
        favVersion++;                  // 让下一次 filterFavorites 不会被防抖吃掉
        requestFilter(true, null);     // 异步遍历+diff,主线程只做增量 dispatch
        return true;
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

    /** 行查找的"上次命中"缓存(见 {@link #findRowByStreamId});只在主线程读写 */
    private String lastRowSid = null;
    private int lastRowPos = -1;

    /** 局部刷新的 payload 标记:只更新缓存进度条,不重绑整行 */
    private static final Object PAYLOAD_CACHE_PROGRESS = new Object();

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
        holder.vSource.setVisibility(showSourceDot ? View.VISIBLE : View.GONE);

        // 按需缓存进度条:仅正在下载缓存的歌曲显示
        String sid = bean.getStreamId();
        Integer prog = (sid != null && !sid.isEmpty()) ? cacheProgress.get(sid) : null;
        if (prog != null && bean.isNetwork()) {
            holder.pbCache.setVisibility(View.VISIBLE);
            holder.pbCache.setIndeterminate(prog < 0);
            if (prog >= 0) {
                holder.pbCache.setProgress(prog);
            }
        } else {
            holder.pbCache.setVisibility(View.GONE);
        }

        // 使用缓存的封面尺寸
        CoverLoader.getInstance().load(bean, holder.ivCover, coverSizeList);

        // 点击监听器在 VH 构造时设置,这里不需要重复创建(减少 GC)

        // 检查是否需要加载更多(用 holder 的 post,Runnable 在 VH 中复用)
        holder.postCheckLoadMore();

        if (PerfLogger.isEnabled()) {
            PerfLogger.log("onBind", System.currentTimeMillis() - t0);
        }
    }

    /**
     * 局部刷新:只更新缓存进度条,**不重绑整行**。
     *
     * 为什么必须分开:下载进度广播一首歌要连发几十次,走整行重绑就等于
     * 每 1% 都重新 setText(标题/副标题/序号)+ 重新 setImageBitmap(封面)
     * —— 封面位图设置是这里最贵的一步,而进度条其实才是唯一变化的控件。
     * 车机 2 核上,这正是"下载时滚列表明显掉帧"的直接来源。
     */
    @Override
    public void onBindViewHolder(@NonNull VH holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        if (position < 0 || position >= data.size()) {
            return;
        }
        MusicBean bean = data.get(position);
        String sid = bean.getStreamId();
        Integer prog = (sid != null && !sid.isEmpty()) ? cacheProgress.get(sid) : null;
        if (prog != null && bean.isNetwork()) {
            holder.pbCache.setVisibility(View.VISIBLE);
            holder.pbCache.setIndeterminate(prog < 0);
            if (prog >= 0) {
                holder.pbCache.setProgress(prog);
            }
        } else {
            holder.pbCache.setVisibility(View.GONE);
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
        ProgressBar pbCache;
        MusicAdapter adapter;

        VH(@NonNull View itemView, MusicAdapter adapter) {
            super(itemView);
            this.adapter = adapter;
            tvIndex = itemView.findViewById(R.id.tv_index);
            ivCover = itemView.findViewById(R.id.iv_cover);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvArtist = itemView.findViewById(R.id.tv_artist);
            vSource = itemView.findViewById(R.id.v_source);
            pbCache = itemView.findViewById(R.id.pb_cache);

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
