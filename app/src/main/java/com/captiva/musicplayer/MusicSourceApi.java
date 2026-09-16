package com.captiva.musicplayer;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 音乐数据源统一接口
 *
 * 目的:让 App 能同时支持多种服务端协议,而不把业务代码绑死在某一种实现上。
 * 目前有两个实现:
 *   - {@link NavidromeApi}  : Subsonic / Navidrome(REST + MD5 token)
 *   - {@link FnMusicApi}    : 飞牛 NAS 音乐服务(/music/api/,GUID 实体 + 签名播放链接)
 *
 * 业务层(MainActivity / MusicService / CoverLoader / MusicSyncManager 等)
 * 只依赖本接口,新增数据源时无需改动业务代码。
 */
public interface MusicSourceApi {

    /** 数据源类型标识,与 NavidromeConfig 中的 server_type 对应 */
    String getSourceType();

    /** 数据源显示名(用于 UI 提示) */
    String getSourceName();

    /**
     * 访问媒体资源(播放流、封面)时需要附加的 HTTP 头
     *
     * 背景:Navidrome 把凭据放在 URL query 里,MediaPlayer 直接打开 URL 就能播;
     * 飞牛的流地址只有 guid,凭据必须走请求头,MediaPlayer 默认不带头会 401。
     *
     * @return 需要附加的请求头;不需要额外头时返回 null 或空 Map
     */
    Map<String, String> getAuthHeaders();

    // ==================== 连通性 ====================

    /** 测试服务器连通性与凭据是否有效 */
    boolean ping();

    // ==================== 专辑 ====================

    /** 获取专辑列表(取前 count 条) */
    List<AlbumBean> getAlbumList(String type, int count);

    /** 获取专辑列表(分页) */
    List<AlbumBean> getAlbumList(String type, int size, int offset);

    /** 获取全部专辑(内部自动翻页) */
    List<AlbumBean> getAllAlbums();

    /** 获取专辑内的歌曲 */
    List<MusicBean> getAlbum(String albumId);

    // ==================== 歌曲 ====================

    /**
     * 按专辑分页拉取歌曲
     * @param albumOffset 专辑偏移
     * @param albumCount  本批专辑数量
     */
    List<MusicBean> getSongsByAlbumPage(int albumOffset, int albumCount);

    /** 分页获取歌曲 */
    List<MusicBean> getSongsPage(int offset, int count);

    /** 获取全部歌曲(内部自动翻页) */
    List<MusicBean> getAllSongs();

    /** 搜索歌曲 */
    List<MusicBean> search(String query, int count);

    /** 随机歌曲 */
    List<MusicBean> getRandomSongs(int count);

    /** 收藏的歌曲 */
    List<MusicBean> getStarredSongs();

    // ==================== 歌词 ====================

    /** 按歌曲 ID 获取结构化歌词 */
    List<LrcEntry> getLyricsBySongId(String songId);

    /** 按艺术家 + 标题获取纯文本歌词(回退方案) */
    String getLyrics(String artist, String title);

    // ==================== 资源 URL / 下载 ====================

    /** 封面图 URL */
    String getCoverArtUrl(String coverArtId, int size);

    /** 流式播放 URL */
    String getStreamUrl(String songId);

    /** 下载歌曲到本地,返回字节数,-1 表示失败 */
    long downloadFile(String songId, File destFile);
}
