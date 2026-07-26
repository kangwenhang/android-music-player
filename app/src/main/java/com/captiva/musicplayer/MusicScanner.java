package com.captiva.musicplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地音乐扫描器
 * 通过 MediaStore.Audio.Media 查询设备中的音频文件
 * MediaStore 在 API 14 即可用,无需运行时权限(安卓 4.0)
 */
public class MusicScanner {

    /**
     * 扫描外部存储中的所有音乐
     */
    public static List<MusicBean> scan(Context context) {
        List<MusicBean> list = new ArrayList<>();
        if (context == null) {
            return list;
        }

        ContentResolver resolver = context.getContentResolver();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        // 查询列(注意:DATA 在 API 29 废弃,但 targetSdk 28 仍可用)
        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
        };

        // 仅查询音乐,且时长超过30秒(过滤铃声、提示音等短音频)
        String selection = MediaStore.Audio.Media.IS_MUSIC + " = 1"
                + " AND " + MediaStore.Audio.Media.DURATION + " > 30000";
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
                    // 构造 content uri,优先用 uri 播放
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
}
