# 科帕奇音乐播放器

适配 **安卓 4.0(API 14)** 车机(科帕奇 2015 款改装车机,8/10 寸 1024×600 横屏)的音乐播放器。

## 功能

- 本地音乐扫描与播放(MediaStore + MediaPlayer)
- **Navidrome 服务器对接**(Subsonic API,支持网络流式播放)
- 本地/网络音乐来源一键切换
- **搜索功能**(点击搜索按钮弹出搜索对话框,内置键盘输入)
- **内置简易输入法**(中英文拼音,适配车机输入法问题)
- **歌词叠加在封面上**(封面放大模糊作为底色背景,歌词在上)
- 播放模式:顺序 / 单曲循环 / 随机
- **播放按钮状态颜色**(播放蓝色 / 暂停红色)
- **水波纹点击反馈**(API 21+ 水波纹,API 14-20 selector 变色)
- 均衡器(系统 Equalizer,5 段频段 + 预设)
- 后台播放 + 通知栏控制
- 方向盘媒体按键响应(RemoteControlClient)
- Navidrome 服务器设置界面(地址/用户名/密码)

## 技术栈

- 原生 Java
- AndroidX appcompat 1.3.1 / recyclerview 1.2.1(支持 API 14)
- AGP 7.4.2 + Gradle 7.5
- minSdk 14 / targetSdk 28 / compileSdk 30

## 分支说明

- `main`:稳定版本
- `test`:测试分支(开发中功能)

## 编译

用 Android Studio 打开本目录,同步后即可编译运行。确保已安装 Android SDK Platform 30。

或通过 GitHub Actions 手动触发构建(workflow_dispatch)。

## Navidrome 配置

1. 打开 App,点击右上角"服务器"按钮
2. 点击输入框,使用内置键盘输入服务器地址(如 `http://192.168.1.100:4533`)
3. 输入用户名和密码
4. 点击"测试连接"验证,然后"保存"
5. 切换到"网络"来源即可浏览播放服务器上的音乐

## 说明

ExoPlayer/Media3 最低需 API 16,无法用于安卓 4.0,故采用系统原生 MediaPlayer。
