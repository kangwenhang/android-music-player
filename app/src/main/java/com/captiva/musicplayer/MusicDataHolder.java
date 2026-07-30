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
    private NavidromeApi navidromeApi;
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

    public NavidromeApi getNavidromeApi() {
        return navidromeApi;
    }

    public void setNavidromeApi(NavidromeApi api) {
        this.navidromeApi = api;
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
