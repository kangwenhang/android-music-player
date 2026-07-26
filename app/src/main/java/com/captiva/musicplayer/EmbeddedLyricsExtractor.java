package com.captiva.musicplayer;

import android.media.MediaMetadataRetriever;
import android.util.Log;

/**
 * 从音乐文件内嵌标签提取歌词
 * 支持 ID3 USLT/SYLT 标签(大多数音乐文件内嵌歌词的存储方式)
 * 兼容 Android 4.0(API 10+)
 */
public class EmbeddedLyricsExtractor {

    private static final String TAG = "EmbeddedLyrics";

    /**
     * 从音乐文件提取内嵌歌词
     * @param filePath 音乐文件路径
     * @return 歌词文本(LRC 格式或纯文本),null 表示无歌词
     */
    public static String extract(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }

        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(filePath);

            // 尝试获取内嵌歌词(API 10+,常量值 15)
            // 部分设备/ROM 可能不支持 METADATA_KEY_LYRICS,用 try-catch 保护
            String lyrics = null;
            try {
                // MediaMetadataRetriever.METADATA_KEY_LYRICS = 15
                lyrics = retriever.extractMetadata(15);
            } catch (Exception e) {
                Log.w(TAG, "extractMetadata LYRICS failed", e);
            }

            // 部分设备歌词存在 AUTHOR 字段或 WRITER 字段
            if (lyrics == null || lyrics.isEmpty()) {
                try {
                    // 尝试从 DESCRIPTION 字段获取(某些播放器写入)
                    // METADATA_KEY_DESCRIPTION = 13
                    String desc = retriever.extractMetadata(13);
                    if (desc != null && desc.contains("[") && desc.contains(":") && desc.contains("]")) {
                        lyrics = desc;
                    }
                } catch (Exception e) {
                    // 忽略
                }
            }

            if (lyrics != null && !lyrics.trim().isEmpty()) {
                Log.d(TAG, "成功提取内嵌歌词: " + lyrics.length() + " 字符");
                return lyrics;
            }

            return null;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "文件路径无效: " + filePath, e);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "提取歌词失败: " + filePath, e);
            return null;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    // 忽略
                }
            }
        }
    }
}
