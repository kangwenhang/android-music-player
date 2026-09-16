package com.captiva.musicplayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局音乐数据持有者
 * 用于在 Activity 间共享已扫描的音乐列表
 */
public class MusicDataHolder {

    private static final MusicDataHolder INSTANCE = new MusicDataHolder();

    private final List<MusicBean> musicList = new ArrayList<>();
    private EqualizerManager equalizerManager;
    /** 当前数据源(Navidrome 或飞牛音乐),业务层只依赖接口 */
    private MusicSourceApi musicSourceApi;
    private boolean navidromeEnabled = false;
    /** 当前播放的歌曲(供 EqualizerActivity 等获取) */
    private MusicBean currentPlayingMusic;

    private MusicDataHolder() {
    }

    public static MusicDataHolder getInstance() {
        return INSTANCE;
    }

    public List<MusicBean> getMusicList() {
        return musicList;
    }

    public void setMusicList(List<MusicBean> list) {
        musicList.clear();
        if (list != null) {
            musicList.addAll(list);
        }
    }

    public EqualizerManager getEqualizerManager() {
        return equalizerManager;
    }

    public void setEqualizerManager(EqualizerManager manager) {
        this.equalizerManager = manager;
    }

    public MusicSourceApi getMusicSourceApi() {
        return musicSourceApi;
    }

    public void setMusicSourceApi(MusicSourceApi api) {
        this.musicSourceApi = api;
    }

    /** @deprecated 使用 {@link #getMusicSourceApi()} */
    @Deprecated
    public NavidromeApi getNavidromeApi() {
        return musicSourceApi instanceof NavidromeApi ? (NavidromeApi) musicSourceApi : null;
    }

    /** @deprecated 使用 {@link #setMusicSourceApi(MusicSourceApi)} */
    @Deprecated
    public void setNavidromeApi(NavidromeApi api) {
        this.musicSourceApi = api;
    }

    public boolean isNavidromeEnabled() {
        return navidromeEnabled;
    }

    public void setNavidromeEnabled(boolean enabled) {
        this.navidromeEnabled = enabled;
    }

    /** 获取当前播放的歌曲 */
    public MusicBean getCurrentPlayingMusic() {
        return currentPlayingMusic;
    }

    /** 设置当前播放的歌曲(由 MusicService 调用) */
    public void setCurrentPlayingMusic(MusicBean music) {
        this.currentPlayingMusic = music;
    }
}
