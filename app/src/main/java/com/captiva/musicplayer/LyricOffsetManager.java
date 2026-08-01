package com.captiva.musicplayer;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * 歌词偏移管理器
 * 为每首歌存储一个手动歌词偏移量(毫秒),用于校正歌词与音频不同步的问题。
 *
 * 偏移规则:
 * - 正值:歌词延后显示(歌词比音频快时用,让歌词等一等)
 * - 负值:歌词提前显示(歌词比音频慢时用,让歌词赶一赶)
 *
 * 存储:JSON 文件,key 为歌曲唯一标识(本地=data|duration,网络=id)
 */
public class LyricOffsetManager {

    private static final String TAG = "LyricOffsetManager";
    private static final String FILE_NAME = "lyric_offsets.json";
    private final File offsetFile;
    private JSONObject offsets;
    private final Object lock = new Object();

    public LyricOffsetManager(Context context) {
        offsetFile = new File(context.getFilesDir(), FILE_NAME);
        load();
    }

    private void load() {
        synchronized (lock) {
            try {
                if (offsetFile.exists()) {
                    StringBuilder sb = new StringBuilder();
                    InputStreamReader reader = new InputStreamReader(
                            new FileInputStream(offsetFile), "UTF-8");
                    char[] buf = new char[4096];
                    int len;
                    while ((len = reader.read(buf)) != -1) {
                        sb.append(buf, 0, len);
                    }
                    reader.close();
                    offsets = new JSONObject(sb.toString());
                } else {
                    offsets = new JSONObject();
                }
            } catch (Exception e) {
                Log.w(TAG, "加载歌词偏移失败", e);
                offsets = new JSONObject();
            }
        }
    }

    private void save() {
        synchronized (lock) {
            OutputStreamWriter writer = null;
            try {
                writer = new OutputStreamWriter(
                        new FileOutputStream(offsetFile), "UTF-8");
                writer.write(offsets.toString());
                writer.flush();
            } catch (Exception e) {
                Log.w(TAG, "保存歌词偏移失败", e);
            } finally {
                if (writer != null) {
                    try { writer.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * 获取歌曲的歌词偏移量(毫秒)
     * @return 偏移量,0 表示无偏移
     */
    public long getOffset(MusicBean bean) {
        if (bean == null) return 0;
        String key = getSongKey(bean);
        if (key == null) return 0;
        synchronized (lock) {
            return offsets.optLong(key, 0);
        }
    }

    /**
     * 设置歌曲的歌词偏移量(毫秒)
     * 偏移为 0 时删除记录
     */
    public void setOffset(MusicBean bean, long offsetMs) {
        if (bean == null) return;
        String key = getSongKey(bean);
        if (key == null) return;
        synchronized (lock) {
            if (offsetMs == 0) {
                offsets.remove(key);
            } else {
                try {
                    offsets.put(key, offsetMs);
                } catch (Exception e) {
                    Log.w(TAG, "设置歌词偏移失败", e);
                }
            }
            save();
        }
    }

    /** 生成歌曲唯一标识(与 MusicAdapter.getSongKey 保持一致) */
    private String getSongKey(MusicBean b) {
        if (!b.isNetwork()) {
            return b.getData() + "|" + b.getDuration();
        }
        return String.valueOf(b.getId());
    }
}
