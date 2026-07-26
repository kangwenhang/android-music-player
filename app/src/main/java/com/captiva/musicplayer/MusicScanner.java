package com.captiva.musicplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地音乐扫描器
 * 支持两种扫描模式:
 * 1. MediaStore 扫描(默认,扫描全部音乐)
 * 2. 自定义目录扫描(递归遍历指定目录下所有音频文件)
 */
public class MusicScanner {

    private static final String TAG = "MusicScanner";

    /** 支持的音频扩展名 */
    private static final String[] AUDIO_EXTS = {
            ".mp3", ".flac", ".wav", ".ogg", ".m4a", ".aac", ".wma", ".ape", ".m4b", ".opus"
    };

    /**
     * 扫描本地音乐
     * - 如果配置了自定义扫描目录,则递归遍历该目录
     * - 否则使用 MediaStore 扫描全部
     * @param context 上下文(用于读取时长过滤配置)
     */
    public static List<MusicBean> scan(Context context) {
        NavidromeConfig config = new NavidromeConfig(context);
        String scanPath = config.getScanPath();

        if (scanPath != null && !scanPath.isEmpty()) {
            // 自定义目录扫描
            Log.d(TAG, "扫描自定义目录: " + scanPath);
            return scanDirectory(context, scanPath);
        } else {
            // MediaStore 全量扫描
            Log.d(TAG, "MediaStore 全量扫描");
            return scanMediaStore(context);
        }
    }

    /**
     * 快速统计本地音乐总数(不加载完整元数据)
     * - MediaStore 模式:用 COUNT 查询
     * - 自定义目录模式:统计音频文件数
     * @return 预估歌曲总数,失败返回 -1
     */
    public static int countLocalMusic(Context context) {
        if (context == null) return -1;
        NavidromeConfig config = new NavidromeConfig(context);
        String scanPath = config.getScanPath();

        if (scanPath != null && !scanPath.isEmpty()) {
            // 自定义目录:快速统计音频文件数
            File rootDir = new File(scanPath);
            if (!rootDir.exists() || !rootDir.isDirectory()) {
                return 0;
            }
            List<File> audioFiles = new ArrayList<>();
            collectAudioFiles(rootDir, audioFiles);
            Log.d(TAG, "快速统计: 目录 " + scanPath + " 找到 " + audioFiles.size() + " 个音频文件");
            return audioFiles.size();
        } else {
            // MediaStore:用 COUNT 查询
            int minDurationSec = config.getMinDuration();
            long minDurationMs = minDurationSec * 1000L;
            ContentResolver resolver = context.getContentResolver();
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String selection = MediaStore.Audio.Media.IS_MUSIC + " = 1"
                    + " AND " + MediaStore.Audio.Media.DURATION + " > " + minDurationMs;
            Cursor cursor = null;
            try {
                cursor = resolver.query(uri, new String[]{"COUNT(*)"}, selection, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int count = cursor.getInt(0);
                    Log.d(TAG, "快速统计: MediaStore 找到 " + count + " 首音乐");
                    return count;
                }
            } catch (Exception e) {
                Log.w(TAG, "countLocalMusic failed", e);
            } finally {
                if (cursor != null) cursor.close();
            }
            return -1;
        }
    }

    /**
     * 递归扫描指定目录下的音频文件
     */
    private static List<MusicBean> scanDirectory(Context context, String path) {
        List<MusicBean> list = new ArrayList<>();

        NavidromeConfig config = new NavidromeConfig(context);
        int minDurationSec = config.getMinDuration();
        long minDurationMs = minDurationSec * 1000L;

        File rootDir = new File(path);
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            Log.w(TAG, "扫描目录不存在: " + path);
            return list;
        }

        List<File> audioFiles = new ArrayList<>();
        collectAudioFiles(rootDir, audioFiles);

        Log.d(TAG, "目录扫描找到 " + audioFiles.size() + " 个音频文件");

        ContentResolver resolver = context.getContentResolver();
        for (File file : audioFiles) {
            try {
                // 尝试从 MediaStore 查询该文件的元数据
                MusicBean bean = queryFromMediaStoreByPath(resolver, file.getAbsolutePath(), minDurationMs);
                if (bean == null) {
                    // MediaStore 没有记录,手动创建
                    bean = createBeanFromFile(file, minDurationMs);
                }
                if (bean != null) {
                    list.add(bean);
                }
            } catch (Exception e) {
                Log.w(TAG, "解析文件失败: " + file.getName(), e);
            }
        }

        // 按标题排序
        java.util.Collections.sort(list, new java.util.Comparator<MusicBean>() {
            @Override
            public int compare(MusicBean a, MusicBean b) {
                return a.getTitle().compareToIgnoreCase(b.getTitle());
            }
        });

        return list;
    }

    /** 递归收集目录下所有音频文件 */
    private static void collectAudioFiles(File dir, List<File> result) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                // 跳过隐藏目录
                if (!f.getName().startsWith(".")) {
                    collectAudioFiles(f, result);
                }
            } else if (isAudioFile(f.getName())) {
                result.add(f);
            }
        }
    }

    /** 判断文件是否为音频 */
    private static boolean isAudioFile(String name) {
        if (name == null || !name.contains(".")) {
            return false;
        }
        String ext = name.substring(name.lastIndexOf(".")).toLowerCase();
        for (String audioExt : AUDIO_EXTS) {
            if (audioExt.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 通过文件路径从 MediaStore 查询元数据 */
    private static MusicBean queryFromMediaStoreByPath(ContentResolver resolver, String filePath, long minDurationMs) {
        try {
            String selection = MediaStore.Audio.Media.DATA + "=?"
                    + " AND " + MediaStore.Audio.Media.IS_MUSIC + "=1"
                    + " AND " + MediaStore.Audio.Media.DURATION + " > " + minDurationMs;
            String[] selectionArgs = new String[]{filePath};

            String[] projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID
            };

            Cursor cursor = resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, selectionArgs, null);

            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        MusicBean bean = new MusicBean();
                        bean.setId(cursor.getLong(cursor.getColumnIndex(MediaStore.Audio.Media._ID)));
                        bean.setTitle(cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)));
                        bean.setArtist(cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)));
                        bean.setAlbum(cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)));
                        bean.setDuration(cursor.getLong(cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)));
                        bean.setData(cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.DATA)));
                        bean.setUri(Uri.withAppendedPath(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                String.valueOf(bean.getId())).toString());
                        return bean;
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaStore查询失败: " + filePath, e);
        }
        return null;
    }

    /** 手动从文件创建 MusicBean(无 MediaStore 记录时) */
    private static MusicBean createBeanFromFile(File file, long minDurationMs) {
        if (file == null || !file.exists()) {
            return null;
        }

        String filePath = file.getAbsolutePath();
        String fileName = file.getName();

        // 去掉扩展名作为标题
        String title = fileName;
        int dotIdx = fileName.lastIndexOf(".");
        if (dotIdx > 0) {
            title = fileName.substring(0, dotIdx);
        }

        // 尝试用 MediaMetadataRetriever 获取时长
        long duration = 0;
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(filePath);
            String durStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null && !durStr.isEmpty()) {
                duration = Long.parseLong(durStr);
            }
            // 尝试获取艺术家和专辑
            String artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM);

            if (duration < minDurationMs) {
                return null; // 时长不足,过滤
            }

            MusicBean bean = new MusicBean();
            bean.setTitle(title);
            bean.setArtist(artist != null ? artist : "未知艺术家");
            bean.setAlbum(album != null ? album : "未知专辑");
            bean.setDuration(duration);
            bean.setData(filePath);
            // 没有 content uri,用文件路径作为 uri
            bean.setUri(filePath);
            return bean;
        } catch (Exception e) {
            Log.w(TAG, "无法读取元数据: " + fileName, e);
            // 即使无法读取元数据,也尝试加入(用文件名)
            if (file.length() > 1024) { // 至少1KB才算有效
                MusicBean bean = new MusicBean();
                bean.setTitle(title);
                bean.setArtist("未知艺术家");
                bean.setAlbum("未知专辑");
                bean.setDuration(0);
                bean.setData(filePath);
                bean.setUri(filePath);
                return bean;
            }
            return null;
        } finally {
            try {
                mmr.release();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * MediaStore 全量扫描
     */
    private static List<MusicBean> scanMediaStore(Context context) {
        List<MusicBean> list = new ArrayList<>();
        if (context == null) {
            return list;
        }

        NavidromeConfig config = new NavidromeConfig(context);
        int minDurationSec = config.getMinDuration();
        long minDurationMs = minDurationSec * 1000L;

        ContentResolver resolver = context.getContentResolver();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " = 1"
                + " AND " + MediaStore.Audio.Media.DURATION + " > " + minDurationMs;
        String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, projection, selection, null, sortOrder);
            if (cursor != null) {
                int idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                int titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int artistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int albumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                int durationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                int dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);

                while (cursor.moveToNext()) {
                    MusicBean bean = new MusicBean();
                    bean.setId(cursor.getLong(idIdx));
                    bean.setTitle(cursor.getString(titleIdx));
                    bean.setArtist(cursor.getString(artistIdx));
                    bean.setAlbum(cursor.getString(albumIdx));
                    bean.setDuration(cursor.getLong(durationIdx));
                    bean.setData(cursor.getString(dataIdx));
                    bean.setUri(Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            String.valueOf(bean.getId())).toString());
                    list.add(bean);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return list;
    }

    /** 获取外部存储根目录(用于设置提示) */
    public static String getDefaultStoragePath() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }
}
