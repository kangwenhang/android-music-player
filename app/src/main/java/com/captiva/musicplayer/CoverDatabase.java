package com.captiva.musicplayer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 封面 SQLite BLOB 缓存
 *
 * 用 SQLite 替代 5000+ 个小文件的磁盘缓存方案:
 * - 所有封面缩略图存为 BLOB 在单个 .db 文件中
 * - SQLite page cache 默认调大到 16MB,热数据全在内存
 * - 读取走 page cache → 零随机磁盘 IO
 * - 批量写入用事务,比逐个写文件快 10 倍以上
 *
 * 兼容性: API 1+ (SQLiteOpenHelper 从 API 1 开始支持)
 */
public class CoverDatabase extends SQLiteOpenHelper {

    private static final String TAG = "CoverDatabase";
    private static final String DB_NAME = "cover_cache.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "covers";

    private static final String COL_KEY = "song_key";
    private static final String COL_DATA = "cover_data";
    private static final String COL_UPDATED = "updated_at";

    /** page cache 页数(每页 4KB,16384 页 = 64MB cache)
     *  SQLite cache_size 单位是页,正值=页数
     *  5000 封面 x 3KB ≈ 15MB,64MB cache 足够全部装入内存 */
    private static final int CACHE_PAGES = 16384;

    private static CoverDatabase instance;
    private SQLiteDatabase readableDb;
    private SQLiteDatabase writableDb;

    private CoverDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    public static synchronized CoverDatabase getInstance(Context context) {
        if (instance == null && context != null) {
            instance = new CoverDatabase(context.getApplicationContext());
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                COL_KEY + " TEXT PRIMARY KEY, " +
                COL_DATA + " BLOB NOT NULL, " +
                COL_UPDATED + " INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // 调大 page cache(每次打开数据库时设置)
        try {
            db.execSQL("PRAGMA cache_size = " + CACHE_PAGES);
        } catch (Exception e) {
            Log.w(TAG, "设置 cache_size 失败", e);
        }
    }

    /**
     * 存入单张封面(同步写入)
     * @param key 缓存 key (与 CoverLoader.getCacheKey 一致)
     * @param bmp 封面 Bitmap
     */
    public void putCover(String key, Bitmap bmp) {
        if (key == null || bmp == null) return;
        try {
            byte[] data = bitmapToBytes(bmp);
            if (data == null) return;

            ContentValues cv = new ContentValues();
            cv.put(COL_KEY, key);
            cv.put(COL_DATA, data);
            cv.put(COL_UPDATED, System.currentTimeMillis());

            getWritable().insertWithOnConflict(TABLE, null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            Log.w(TAG, "putCover failed: " + key, e);
        }
    }

    /**
     * 批量存入封面(事务写入,比逐条快 10 倍)
     * @param entries 封面条目列表
     */
    public void putCoversBatch(List<CoverEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        SQLiteDatabase db = getWritable();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            for (CoverEntry entry : entries) {
                byte[] data = bitmapToBytes(entry.bitmap);
                if (data == null) continue;

                cv.clear();
                cv.put(COL_KEY, entry.key);
                cv.put(COL_DATA, data);
                cv.put(COL_UPDATED, System.currentTimeMillis());
                db.insertWithOnConflict(TABLE, null, cv,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.w(TAG, "putCoversBatch failed", e);
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 从 SQLite 读取封面 Bitmap
     * 走 page cache,命中时零磁盘 IO
     * @param key 缓存 key
     * @param targetSize 目标尺寸(用于计算采样率)
     * @return Bitmap 或 null
     */
    public Bitmap getCover(String key, int targetSize) {
        if (key == null) return null;
        Cursor cursor = null;
        try {
            cursor = getReadable().query(
                    TABLE,
                    new String[]{COL_DATA},
                    COL_KEY + " = ?",
                    new String[]{key},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                byte[] data = cursor.getBlob(0);
                if (data == null || data.length == 0) return null;

                // 单次解码:从 byte[] 直接解码,不再二次读文件
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, opts);

                if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;

                opts.inSampleSize = calculateSampleSize(
                        opts.outWidth, opts.outHeight, targetSize);
                opts.inJustDecodeBounds = false;
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                opts.inPurgeable = true;
                opts.inTempStorage = new byte[8 * 1024];

                return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            }
        } catch (Exception e) {
            Log.w(TAG, "getCover failed: " + key, e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    /**
     * 检查 key 是否已缓存(不读取 BLOB,只查索引,极快)
     */
    public boolean hasCover(String key) {
        if (key == null) return false;
        Cursor cursor = null;
        try {
            cursor = getReadable().query(
                    TABLE,
                    new String[]{COL_KEY},
                    COL_KEY + " = ?",
                    new String[]{key},
                    null, null, null, "1");
            return cursor != null && cursor.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 批量检查哪些 key 已缓存
     * @param keys 待检查的 key 列表
     * @return 已缓存的 key 集合
     */
    public java.util.Set<String> findCachedKeys(List<String> keys) {
        java.util.Set<String> result = new java.util.HashSet<>();
        if (keys == null || keys.isEmpty()) return result;

        SQLiteDatabase db = getReadable();
        // 分批查询(每批 500 个,避免 SQL IN 子句过长)
        int batchSize = 500;
        for (int i = 0; i < keys.size(); i += batchSize) {
            int end = Math.min(i + batchSize, keys.size());
            StringBuilder sb = new StringBuilder();
            String[] args = new String[end - i];
            for (int j = i; j < end; j++) {
                if (sb.length() > 0) sb.append(',');
                sb.append('?');
                args[j - i] = keys.get(j);
            }
            Cursor cursor = null;
            try {
                cursor = db.query(TABLE, new String[]{COL_KEY},
                        COL_KEY + " IN (" + sb.toString() + ")",
                        args, null, null, null);
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        result.add(cursor.getString(0));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "findCachedKeys batch failed", e);
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        return result;
    }

    /** 清空所有封面缓存 */
    public void clear() {
        try {
            getWritable().delete(TABLE, null, null);
        } catch (Exception e) {
            Log.w(TAG, "clear failed", e);
        }
    }

    /** 获取已缓存封面数量 */
    public int getCount() {
        Cursor cursor = null;
        try {
            cursor = getReadable().rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "getCount failed", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    /** 关闭数据库连接 */
    public void closeDb() {
        try {
            if (readableDb != null) {
                readableDb.close();
                readableDb = null;
            }
            if (writableDb != null) {
                writableDb.close();
                writableDb = null;
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== 内部方法 ====================

    private SQLiteDatabase getReadable() {
        if (readableDb == null || !readableDb.isOpen()) {
            readableDb = getReadableDatabase();
        }
        return readableDb;
    }

    private SQLiteDatabase getWritable() {
        if (writableDb == null || !writableDb.isOpen()) {
            writableDb = getWritableDatabase();
        }
        return writableDb;
    }

    /** Bitmap → JPEG byte[] (压缩到小尺寸) */
    private byte[] bitmapToBytes(Bitmap bmp) {
        if (bmp == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, bos);
        return bos.toByteArray();
    }

    /** 计算采样率 */
    private int calculateSampleSize(int width, int height, int target) {
        if (target <= 0 || width <= 0 || height <= 0) return 1;
        int sample = 1;
        while (width / sample > target || height / sample > target) {
            sample *= 2;
        }
        return sample;
    }

    /** 批量写入的封面条目 */
    public static class CoverEntry {
        public final String key;
        public final Bitmap bitmap;

        public CoverEntry(String key, Bitmap bitmap) {
            this.key = key;
            this.bitmap = bitmap;
        }
    }
}
