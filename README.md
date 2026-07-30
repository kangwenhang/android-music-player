# 科帕奇音乐播放器

一款专为 **安卓 4.2.2(API 17)** 老款车机打造的本地+网络音乐播放器,适配科帕奇 2015 款改装车机(7 寸 1024×600 横屏电阻屏)。支持本地音乐与 Navidrome 服务器双模式,针对低性能硬件做了深度优化。

## 主要功能

### 播放
- 本地音乐扫描与播放(MediaStore + MediaPlayer)
- **Navidrome 服务器对接**(Subsonic API,网络流式播放)
- 本地/网络音乐来源一键切换
- 播放模式:顺序播放 / 单曲循环 / 随机播放
- **打开自动播放**:可设置启动时自动恢复上次播放的歌曲和进度
- 后台播放 + 通知栏控制
- 方向盘媒体按键响应

### 搜索与收藏
- **实时搜索**:支持按歌曲名、歌手名过滤(本地即时过滤,网络防抖搜索)
- **收藏夹管理**:一键收藏/取消收藏,独立收藏列表浏览

### 歌词
- **歌词叠加封面**:封面放大模糊作为背景,歌词叠加在上层滚动显示
- 支持内嵌歌词提取与本地 .lrc 文件加载
- 歌词缓存,二次打开秒加载
- 大字体歌词显示,适合车机远距离阅读

### 均衡器
- 系统 Equalizer,5 段频段调节
- **预设模式**:关闭、流行、摇滚、爵士、古典、低音增强、高音增强
- **自定义模式**:可新建/保存/删除自定义均衡器配置
- **单曲 EQ 绑定**:为每首歌单独绑定均衡器模式,切歌自动切换
- 底部控制栏快捷切换按钮,实时显示当前 EQ 模式

### 网络与缓存
- **网络歌曲缓存优先加载**:切换网络模式秒开,后台静默更新完整列表
- **歌曲列表本地缓存**:断网仍可浏览上次缓存的歌曲
- **封面三级缓存**(内存 / 磁盘 / 网络),U 盘音乐封面预加载优化
- **歌词本地缓存**,避免重复网络请求
- 服务器状态实时监控,断线 30 秒自动重连

## 车机适配优化

- **电阻屏优化**:大按钮(64/80dp)+ 大间距 + 大字体,便于指尖触控
- **U 盘音乐性能优化**:无封面歌曲缓存跳过,滚动停止后预加载封面
- **启动优化**:延迟重初始化,避免白屏卡顿
- **全屏沉浸模式**:隐藏状态栏和虚拟导航键
- 横屏锁定,适配 1024×600 分辨率
- 禁用列表动画,减少低端 GPU 负担

## 技术栈

| 项目 | 版本 |
|------|------|
| 语言 | 原生 Java |
| AppCompat | 1.3.1 |
| RecyclerView | 1.2.1 |
| AGP | 7.4.2 |
| Gradle | 7.5 |
| minSdk | 17(安卓 4.2.2) |
| targetSdk | 28 |
| compileSdk | 30 |

采用系统原生 MediaPlayer 而非 ExoPlayer/Media3,保证在安卓 4.2.2 上的兼容性和稳定性。

## 编译

### 本地编译

用 Android Studio 打开本目录,同步 Gradle 后即可编译运行。确保已安装:
- Android SDK Platform 30
- Build-Tools 30.0.3
- JDK 17

```bash
# 命令行编译
gradle assembleDebug
```

APK 输出路径:`app/build/outputs/apk/debug/`

### CI 自动构建

推送到 `main` 分支后,GitHub Actions 自动构建 Debug APK 并发布 Release。也可通过 `workflow_dispatch` 手动触发。

版本号自动生成:
- `versionCode` = git commit 数量(每次提交递增,支持覆盖安装)
- `versionName` = `1.0.<commit数>(<短hash>)`

## Navidrome 配置

1. 打开 App,点击右上角「设置」按钮
2. 选择「服务器设置」
3. 输入服务器地址(如 `http://192.168.1.100:4533`)、用户名和密码
4. 点击「测试连接」验证,然后「保存」
5. 点击顶栏「本地/网络」按钮切换到网络来源即可浏览播放

## 项目结构

```
app/src/main/java/com/captiva/musicplayer/
├── MainActivity.java          # 主界面(列表+歌词+控制栏)
├── MusicService.java          # 后台播放服务
├── MusicAdapter.java          # 歌曲列表适配器
├── MusicScanner.java          # 本地音乐扫描
├── MusicSyncManager.java      # Navidrome 同步管理
├── NavidromeApi.java          # Subsonic API 封装
├── NavidromeConfig.java       # 配置持久化(SharedPreferences)
├── CoverLoader.java           # 封面三级缓存加载器
├── LrcView.java               # 歌词滚动视图
├── LrcParser.java             # LRC 歌词解析
├── LrcEntry.java              # 歌词条目数据
├── LyricCache.java            # 歌词缓存
├── EmbeddedLyricsExtractor.java # 内嵌歌词提取
├── EqualizerActivity.java     # 均衡器界面
├── EqualizerManager.java      # 均衡器管理(预设/自定义/单曲绑定)
├── FavoriteManager.java       # 收藏管理
├── SongCache.java             # 歌曲列表缓存
├── ServerStatusMonitor.java   # 服务器状态监控
├── ServerSettingsActivity.java # 服务器设置界面
├── SyncActivity.java          # 同步进度界面
├── MusicDataHolder.java       # 全局数据持有者
├── MusicBean.java             # 歌曲数据模型
├── AlbumBean.java             # 专辑数据模型
├── PlayMode.java              # 播放模式枚举
├── VerticalSeekBar.java       # 垂直滑块(EQ频段)
├── MediaButtonReceiver.java   # 媒体按键接收
└── App.java                   # Application 入口
```

## License

MIT
