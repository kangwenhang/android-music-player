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
 * key 规则与 MusicAdapter.getSongKey 一致:
 * - 本地歌曲: local_{规范化路径}
 * - 网络歌曲: net_{streamId}
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

    /** 生成歌曲唯一 key(使用 MusicBean 缓存,避免重复文件系统 I/O) */
    public static String getSongKey(MusicBean bean) {
        return bean.getCachedKey();
    }

    /** 是否已收藏 */
    public boolean isFavorite(MusicBean bean) {
        return favoriteSet.contains(getSongKey(bean));
    }

    /** 是否已收藏(key 版本) */
    public boolean isFavorite(String key) {
        return favoriteSet.contains(key);
    }

    /** 切换收藏状态,返回切换后是否已收藏 */
    public boolean toggleFavorite(MusicBean bean) {
        return toggleFavorite(getSongKey(bean));
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

    /** 移除收藏 */
    public void removeFavorite(MusicBean bean) {
        String key = getSongKey(bean);
        if (favoriteSet.contains(key)) {
            favoriteSet.remove(key);
            save();
        }
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
