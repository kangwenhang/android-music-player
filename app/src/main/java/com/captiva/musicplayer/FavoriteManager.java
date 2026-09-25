package com.captiva.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 收藏管理器
 * 使用 SharedPreferences 存储收藏的歌曲 key
 * key 规则与 MusicBean.getIdentityKey 一致(跨云端/本地列表同键):
 * - 网络歌曲 / 本地已下载(有 streamId): net_{streamId}
 * - 纯本地歌曲: local_{规范化路径}
 *
 * 兼容旧数据:历史版本本地歌曲的收藏键是 local_{规范化路径}。同一首歌下载后
 * 身份键变为 net_{streamId},旧键仍然保留在集合里 —— isFavorite 会同时匹配
 * 新旧两个键,取消收藏时两个键一起移除,老用户的收藏不会"消失"。
 */
public class FavoriteManager {

    private static final String PREFS_NAME = "favorites";
    private static final String KEY_FAVORITES = "favorite_keys";

    private final SharedPreferences prefs;
    private Set<String> favoriteSet = new LinkedHashSet<>();

    public FavoriteManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    /** 从 SharedPreferences 加载收藏列表 */
    private void load() {
        Set<String> stored = prefs.getStringSet(KEY_FAVORITES, null);
        if (stored != null) {
            favoriteSet = new LinkedHashSet<>(stored);
        }
    }

    /** 保存到 SharedPreferences */
    private void save() {
        prefs.edit().putStringSet(KEY_FAVORITES, favoriteSet).apply();
    }

    /** 生成歌曲跨列表身份键(使用 MusicBean 缓存,避免重复文件系统 I/O) */
    public static String getSongKey(MusicBean bean) {
        return bean.getIdentityKey();
    }

    /**
     * 旧版本地收藏键(local_{规范化路径})。
     * 仅对"本地且有 streamId"的 bean 有意义 —— 身份键已变成 net_{streamId},
     * 但老收藏数据存的是 local_ 键,匹配/取消时需要兼顾。
     */
    private static String legacyLocalKey(MusicBean bean) {
        if (bean.isNetwork()) return null;
        String sid = bean.getStreamId();
        if (sid == null || sid.isEmpty()) return null; // 纯本地:身份键本身就是 local_,无 legacy
        String cp = bean.getCachedCanonicalPath();
        return (cp != null && !cp.isEmpty()) ? "local_" + cp : null;
    }

    /** 是否已收藏(身份键或旧本地键任一命中) */
    public boolean isFavorite(MusicBean bean) {
        if (favoriteSet.contains(getSongKey(bean))) return true;
        String legacy = legacyLocalKey(bean);
        return legacy != null && favoriteSet.contains(legacy);
    }

    /** 是否已收藏(key 版本) */
    public boolean isFavorite(String key) {
        return favoriteSet.contains(key);
    }

    /** 切换收藏状态,返回切换后是否已收藏(新旧键一起处理) */
    public boolean toggleFavorite(MusicBean bean) {
        String key = getSongKey(bean);
        String legacy = legacyLocalKey(bean);
        boolean wasFav = favoriteSet.contains(key)
                || (legacy != null && favoriteSet.contains(legacy));
        if (wasFav) {
            favoriteSet.remove(key);
            if (legacy != null) favoriteSet.remove(legacy);
            save();
            return false;
        }
        favoriteSet.add(key);
        save();
        return true;
    }

    /** 切换收藏状态(key 版本),返回切换后是否已收藏 */
    public boolean toggleFavorite(String key) {
        if (favoriteSet.contains(key)) {
            favoriteSet.remove(key);
            save();
            return false;
        } else {
            favoriteSet.add(key);
            save();
            return true;
        }
    }

    /** 添加收藏 */
    public void addFavorite(MusicBean bean) {
        String key = getSongKey(bean);
        if (!favoriteSet.contains(key)) {
            favoriteSet.add(key);
            save();
        }
    }

    /** 移除收藏(新旧键一起移除) */
    public void removeFavorite(MusicBean bean) {
        String key = getSongKey(bean);
        String legacy = legacyLocalKey(bean);
        boolean changed = false;
        if (favoriteSet.contains(key)) {
            favoriteSet.remove(key);
            changed = true;
        }
        if (legacy != null && favoriteSet.contains(legacy)) {
            favoriteSet.remove(legacy);
            changed = true;
        }
        if (changed) save();
    }

    /** 获取全部收藏 key(不可变) */
    public Set<String> getAllFavorites() {
        return Collections.unmodifiableSet(favoriteSet);
    }

    /** 收藏数量 */
    public int size() {
        return favoriteSet.size();
    }

    /** 清空收藏 */
    public void clear() {
        favoriteSet.clear();
        save();
    }
}
