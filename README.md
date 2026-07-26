# 科帕奇音乐播放器

适配 **安卓 4.0(API 14)** 车机(科帕奇 2015 款改装车机,8/10 寸 1024×600 横屏)的本地音乐播放器。

## 功能

- 本地音乐扫描与播放(MediaStore + MediaPlayer)
- 歌词显示(自动加载同名 .lrc,支持 UTF-8/GBK)
- 播放模式:顺序 / 单曲循环 / 随机
- 文件夹分类浏览
- 均衡器(系统 Equalizer,5 段频段 + 预设)
- 后台播放 + 通知栏控制
- 方向盘媒体按键响应(RemoteControlClient)

## 技术栈

- 原生 Java
- AndroidX appcompat 1.3.1 / recyclerview 1.2.1(支持 API 14)
- AGP 7.4.2 + Gradle 7.5
- minSdk 14 / targetSdk 28 / compileSdk 30

## 编译

用 Android Studio 打开本目录,同步后即可编译运行。确保已安装 Android SDK Platform 30。

## 说明

ExoPlayer/Media3 最低需 API 16,无法用于安卓 4.0,故采用系统原生 MediaPlayer。