package com.captiva.musicplayer;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件夹扫描器
 * 按音乐文件所在的文件夹进行分组
 */
public class FolderScanner {

    /** 文件夹信息 */
    public static class Folder {
        public String path;       // 完整路径
        public String name;       // 显示名
        public int count;         // 歌曲数
        public List<MusicBean> songs = new ArrayList<>();
    }

    /**
     * 基于已扫描的歌曲列表,按所在文件夹分组
     */
    public static List<Folder> groupByFolder(List<MusicBean> songs) {
        Map<String, Folder> map = new HashMap<>();
        if (songs != null) {
            for (MusicBean song : songs) {
                String data = song.getData();
                if (data == null || data.isEmpty()) {
                    continue;
                }
                File f = new File(data);
                File parent = f.getParentFile();
                String dirPath = parent != null ? parent.getAbsolutePath() : "/";
                String dirName = parent != null ? parent.getName() : "根目录";

                Folder folder = map.get(dirPath);
                if (folder == null) {
                    folder = new Folder();
                    folder.path = dirPath;
                    folder.name = dirName;
                    map.put(dirPath, folder);
                }
                folder.songs.add(song);
                folder.count = folder.songs.size();
            }
        }
        return new ArrayList<>(map.values());
    }
}