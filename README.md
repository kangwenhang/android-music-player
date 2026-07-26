# 科帕奇音乐播放器

适配 **安卓 4.0(API 14)** 车机(科帕奇 2015 款改装车机,8/10 寸 1024×600 横屏)的音乐播放器。

## 功能

- 本地音乐扫描与播放(MediaStore + MediaPlayer)
- **Navidrome 服务器对接**(Subsonic API,支持网络流式播放)
- 本地/网络音乐来源一键切换
- **本地音乐搜索**(实时过滤歌曲、艺术家、专辑)
- **歌曲封面显示**(本地嵌入式封面 + Navidrome 封面)
- 歌词显示(自动加载同名 .lrc,支持 UTF-8/GBK,大字体界面)
- 播放模式:顺序 / 单曲循环 / 随机
- **播放按钮状态颜色**(播放蓝色 / 暂停红色)
- **按键震动反馈**
- 均衡器(系统 Equalizer,5 段频段 + 预设)
- 后台播放 + 通知栏控制
- 方向盘媒体按键响应(RemoteControlClient)
- Navidrome 服务器设置界面(地址/用户名/密码)

## 技术栈

- 原生 Java
- AndroidX appcompat 1.3.1 / recyclerview 1.2.1(支持 API 14)
- AGP 7.4.2 + Gradle 7.5
- minSdk 14 / targetSdk 28 / compileSdk 30

## 编译

用 Android Studio 打开本目录,同步后即可编译运行。确保已安装 Android SDK Platform 30。

或通过 GitHub Actions 自动构建,推送代码到 main 分支即可触发。

## Navidrome 配置

1. 打开 App,点击右上角服务器设置图标
2. 填入 Navidrome 服务器地址(如 `http://192.168.1.100:4533`)
3. 输入用户名和密码
4. 点击保存,然后切换到"网络"来源即可浏览播放服务器上的音乐

## 说明

ExoPlayer/Media3 最低需 API 16,无法用于安卓 4.0,故采用系统原生 MediaPlayer。
