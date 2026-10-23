# cli/ — 本项目网络连接器工具集(本地留存,不入 GitHub)

本会话/本项目实际用到的网络工具,打包在此方便复制到其他环境使用。
`/cli/` 与 `/apk/` 均已加入 .gitignore,不要提交。

统一依赖:Python 3.6+(纯标准库,无第三方包)。
统一凭据:需要 GitHub token 的脚本从环境变量 `GH_TOKEN_X` 读取
(本机取法:`git-credential-manager get` 的 password 字段)。

## 工具清单

### 1. push_via_api.py — git push 被代理卡死时的 API 直推

原理:commit SHA 由内容决定,把 tree/blob/commit 用 REST API 原样重建后
PATCH ref,远端 SHA 与本地 HEAD 完全一致,不产生分叉、不需要 force。

```bash
export GH_TOKEN_X=$(printf "protocol=https\nhost=github.com\n\n" \
  | git-credential-manager get 2>/dev/null | sed -n 's/^password=//p')
python push_via_api.py            # 只校验各层 SHA(会建对象,不 PATCH ref)
python push_via_api.py --push     # 校验通过才更新远端分支
```

环境变量:`REPO_DIR`(默认当前目录)、`REPO_OWNER`、`REPO_NAME`、`REPO_BRANCH`。
要点:先试 `git -c http.version=HTTP/1.1 push`(很多"挂死"其实是 HTTP/2 被代理搞坏,
加这一条就秒过);`ls-remote 通 ≠ push 能通`,确认传输层卡死再走 API。

### 2. poll_ci.py — 轮询 GitHub Actions 结果

```bash
python poll_ci.py                        # 轮询本地 HEAD 的构建,exit 0=绿
python poll_ci.py --sha <commit> --branch main
```

坑:`?branch=X&per_page=1` 排序不稳定,必须按 `head_sha` 匹配(默认取本地 HEAD);
`run_number` 不是 `id`。

### 3. publish_apk.py — 车机自更新包下载/生成(即 car-apk-publish 技能的脚本)

```bash
python publish_apk.py --channel debug    # 调试包: pre-latest 固定入口 Release
python publish_apk.py --channel release  # 正式包: 最新非预发布 Release
```

下载 APK(体积 sanity check 防半截包)→ 算 sha256 → 从 release 正文解析
versionName/versionCode → 生成 `update-debug.json` / `update.json`,
默认输出到**仓库根目录下的 `apk/`**(`--out` 可改)。

加 `--upload` 会紧接着把这两个文件推到 TeraCloud。
**但常规流程已经不这么做了** —— 2026-10-24 起上传改在 GitHub Actions 里自动完成
(见下一节),本地这条命令只用于"手动补传一次"的场景。

```bash
python publish_apk.py --channel debug --upload     # 仅手动补传时用
```

### 4. teracloud_publish.py — CI 内的上传(**正式分发路径**,2026-10-24 起)

车机自更新的 APK + `update*.json` 必须和"那一次构建的 APK"严格同源,
人肉在本地跑脚本迟早会漏传或传错版本,所以上传搬进了
`.github/workflows/build.yml`,每次构建自动闭环。

流程(CI 里 `Upload to TeraCloud (车机自更新源)` 这一步):

1. `aapt dump badging` 直接从 APK 里读 `versionName` / `versionCode`
   —— **不重新推导**,杜绝"包里 447、JSON 写 447 但 sha256 是 446 的";
2. 从本轮生成的 release 正文里抽 `## 本次更新` 小节作为更新说明;
3. 算 sha256 → 生成 `update-debug.json` / `update.json`;
4. PUT 到 `https://yura.teracloud.jp/dav/apk/`,并回读远端大小校验。

**凭据(必须先在仓库里配好)**:
Settings → Secrets and variables → Actions 里填两个值:

| 名称 | 内容 |
|---|---|
| `WEBDAV_USER` | TeraCloud 账号(如 `k1483162508`) |
| `WEBDAV_PASS` | TeraCloud 密码 / 应用密码 |

放 **Secrets**(加密、日志里自动打码)或 **Variables**(明文、改起来方便)都能被读到,
脚本按 `secrets.X || vars.X` 的顺序取。

**没配会怎样**:脚本打印提示后 `exit 0`,**构建不会变红**,可以在填好
secrets 前就先把这套流水线推上去。一旦配了却上传失败(密码错/网络错),
才 `exit 1` 把构建搞红 —— 因为那时"车机拿不到新包"是真故障。

本脚本刻意**自包含、零 Windows 依赖**(凭据只从环境变量取),
因为它要跑在 ubuntu runner 上,而 `webdav_cred.py` 在模块级就
`from ctypes import wintypes`,在 Linux 上 import 直接失败。

```bash
# 手动复现 CI 的行为(先把凭据导进环境变量)
WEBDAV_USER=... WEBDAV_PASS=... python3 cli/teracloud_publish.py \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --channel debug --version-name 6.2.447-pre --version-code 100413
```

### 5. webdav_upload.py — TeraCloud 上传(手动/本地,凭据走 DPAPI 或环境变量)

```bash
python webdav_upload.py --status                    # 测认证 + 列目录
python webdav_upload.py apk/app-debug.apk apk/update-debug.json
```

上传走 `https://yura.teracloud.jp/dav/apk`,**Basic 认证**(官方强制,
`/dav/` 全路径匿名访问一律 401)。凭据存在 `cli/.webdav_cred`,
由 `webdav_cred.py` 用 **Windows DPAPI** 加密(密钥派生自 Windows 账户,
不落盘任何明文,别人拷走文件也解不开):

```bash
python webdav_cred.py --user k1483162508 --pass <应用密码>   # 存(不回显密码)
python webdav_cred.py --check                                # 只打印密码长度
```

上传后回读远端大小做完整性校验;APK 小于 100KB 会告警(防半截包)。

## TeraCloud 公开分享下载协议(2026-10-23 逆向 + curl 实测)

车机**不**走 WebDAV(那会把账号密码写进 APK)。它在 TeraCloud 网页端
对 `apk` 文件夹建的**只读公开分享**上下载:

分享页 `https://yura.teracloud.jp/share/1152388ab50a47a8`
→ 页面 JS(`/share/js/all.js`)里 `SHARE_URL = "/v2/api/share/public/" + shareId`
→ 两步拿到文件:

1. **换票据**:`POST /v2/api/share/public/<id>`
   头 `Accept: application/json` + `Content-Type: application/x-www-form-urlencoded`,
   **空 body**(必须 POST,GET 会被拒)→ 响应头
   `Set-Cookie: tcs_ticket=<12位hex>;Version=1;Path=/ds/dav/;Max-Age=3600;Secure`
   → 票据 **1 小时过期**。
2. **取文件**:`GET /ds/dav/<id>/<文件名>`,带 `Cookie: tcs_ticket=<票据>`。
   无票据一律 401;文件不存在 404;HEAD 可用(200/404)。
   响应 `Content-Type` 正常(APK 是 `application/vnd.android.package-archive`)。

补充实测:
- 直接匿名 GET `/ds/dav/...` 是 401,**必须先换票据**(这点和 fnOS 的
  `Cookie: <shareId>=<token>` 是同类设计)。
- POST 出来的 `location` 是 SPA 路由(`/sbrowser/#...`),不是文件直链,
  真正下载口就是上面第 2 步。
- TeraCloud **只接受 TLS 1.2**(TLS 1.0/1.1 握手直接失败),
  且必须用 http → 会 301 到 https。
- 证书链:叶 `*.teracloud.jp` ← `GlobalSign GCC R46 AlphaSSL CA 2025`
  ← `GlobalSign Root R46`(2019-2034,**交叉签自 R6**)。
  2015 年车机的根证书列表里没有它 → app 内置这条链
  (`app/src/main/res/raw/teracloud_ca.pem`)作兜底信任锚。
- 服务端同时支持 CBC 套件(`AES128-SHA` / `ECDHE-RSA-AES128-SHA`),
  老设备的 TLS 1.2 能协商得上。

## 附录:fnOS 旧分发链路(2026-10-23 停用,仅留档)

1. **resolve**:`POST http://share.fnnas.net/api/v1/fn/share`,body `{"shareId":..}`,
   http 明文可用(老安卓信任库无碍)→ `data.ipv4[]`(LAN IP)、`data.fn[]`(中继域名)、
   `data.port.httpPort`(5666)。
2. **token**:`GET {base}/s/<shareId>` 页面 HTML 内嵌
   `<script id="share-data">{"data":{"token":..}}`;token 每次页面加载都变,
   list/download 必须用同一会话的 token 连续调用。base 依次试
   `http://<ipv4>:<httpPort>` → `https://<fn域名>`。
3. **list**:`POST {base}/s/<id>/api/v1/share/list`,
   body `{"shareId","path","fileId"}`;header `Auth:<token>` + `authx`。
   进子目录**必须带 fileId**,path 做路径校验;`files:null` 表示空目录(不是错误)。
4. **download**:`POST {base}/s/<id>/api/v1/share/download`,
   body `{"files":[{"path":"父路径/文件名","fileId":N}],"shareId","downloadFilename"}`
   → `data.path`(可能相对路径,按 base origin 补全)→ GET 下载。
   **【关键】GET 必须带 `Cookie: <shareId>=<token>`,否则 nginx 直接
   400(空 body)!这是与 URL `?token=` 相互独立的第二道校验——浏览器
   由分享页 JS 自动写入 cookie,所以浏览器天然成功;脚本必须手动补。
   cookie 值与 URL token 不必相同,都是有效 token 即可。**
5. **签名**(两把 key 同一前缀):
   ```
   PREFIX = NDzZTVxnRKP8Z0jXg1VAMonaG8akvh
   KEY_CONNECT = zIGtkc3dqZnJpd29qZXJqa2w7c   # fn/share 解析接口
   KEY_SHARE   = 814&d6470861a4cfbbb4fe2fd3f$6581f6  # NAS 侧 list/download
   sign = MD5(PREFIX _ urlPath _ nonce(6位) _ ts(毫秒) _ MD5(body) _ key)
   authx = nonce=..&timestamp=..&sign=..
   ```
   body 必须与实际发送的请求体**逐字节一致**(先序列化再签名再发送同一字符串)。
   KEY_CONNECT 在官方 JS 里是 XOR 247 混淆的字节数组(34 字节)。
6. 分享目录当前结构:根目录/`android-music-player/`(app 端运行时扫描 update.json
   与 update-debug.json,见 UpdateChecker.java 的频道规则)。

### A2. fnos_upload.py — fnOS 登录+文件上传 CLI(2026-10-05 逆向, 全链路实测, 已停用)

```bash
# venv(依赖 websocket-client/cryptography/requests):
PY=C:/Users/kangw/.workbuddy/binaries/python/envs/default/Scripts/python.exe
$PY cli/fnos_upload.py apk/app-debug.apk apk/update-debug.json   # 上传到分享目录
$PY cli/fnos_upload.py --status                                  # 只测登录链路
# Git Bash 传绝对路径参数要 MSYS_NO_PATHCONV=1,否则 /vol1/... 被转义成本地盘符路径
```

协议速记(完整版见 fnos_upload.py docstring):
- 登录:`ws://<host>:5666/websocket?type=main` → getRSAPub → 发
  `{req:"encrypted",iv,rsa,aes}`(RSA=**PKCS1v15**/JSEncrypt,AES-256-CBC/PKCS7,
  payload 必带 `ver:2` 和 `si`) → 明文响应含 secret(密文)/ticket
- secret 用登录 AES key/iv 解密 → **16 字节 HMAC key**
- `POST /app/ticket {ticket}` → Set-Cookie **ost/osrt**(上传认证靠它)
- `POST /upload` multipart `trim-upload-file`,头:
  `Trim-Path=encodeURI(目录+uploadName)`(**临时名!**)、`Trim-Sign=b64(HMAC-SHA256(Trim-Path, secret16B))`、
  Trim-From/Trim-Overwrite/Trim-Mtim;Trim-Token 登录路径无值→不发
- **checkUpload 返回 `uploadName`**(服务器分配的临时名如 `x.apk.~#1`),
  Trim-Path 必须用它;单片传完服务器自动 rename 成正式名。
  直接用正式名当 Trim-Path 会卡在 `.~#N` 临时态不落地
- **错误码**:403=无写权限 497=路径无效(**必须真实绝对路径**如
  `/vol1/1001/apk/android-music-player/x.apk`,分享命名空间 `/xxx` 会 497)
  498=与目录重名 499=路径过长
- 前置 WS RPC `file.checkUpload {size,path,overwrite}`;目录列表
  `file.lsDir {dir}`(只认真实路径);"我的文件"是跨空间聚合视图,非真实路径
- 清理临时垃圾: WS `file.rm {files:[全路径...],moveToTrashbin:false}`
- 权限:kwj 的 home 卷是 **/vol1/1001**(uid=1001),apk 共享文件夹即
  `/vol1/1001/apk/android-music-player`,kwj 对其有写权(实测 200)

## 典型组合拳(发一个新调试包到车机)

```bash
# 1. 提交推送(commit 后)
git -c http.version=HTTP/1.1 push origin local-test || python cli/push_via_api.py --push
# 2. 等 CI —— 构建 + 打 Release + 上传 TeraCloud 全在 CI 里完成
python cli/poll_ci.py
# 3. (人工)车机 关于→检测更新

# 只有想把产物同步到本地时才需要:
python cli/publish_apk.py --channel debug        # 只下 metadata,不带 --upload
```

## 发布正式版到车机(main 分支)

```bash
# 1. 在 main 上打 tag(正式版版本名取自它)
git tag v6.2 && git push origin v6.2
# 2. CI 会自动: assembleRelease → 打 Release → 上传 app-release.apk + update.json
# 3. (人工)车机 关于→检测更新
```

> 前提:仓库的 `WEBDAV_USER` / `WEBDAV_PASS` 已配好,否则 CI 会跳过上传
> (打印提示、构建不变红)。

## 已安装(2026-10-11)

包装命令装在 `C:/Users/kangw/.workbuddy/bin/`(已加入用户 PATH,.cmd + bash 双格式,
新开终端生效)。REPO_DIR 默认本项目目录,token 自动读 cli/.gh_token:

| 命令 | 脚本 | Python |
|---|---|---|
| mp-push | push_via_api.py | managed 3.13.12 |
| mp-ci | poll_ci.py | managed 3.13.12 |
| mp-apk | publish_apk.py | managed 3.13.12 |
| mp-share | fnos_share.py(旧,已停用) | managed 3.13.12 |
| mp-upload | webdav_upload.py(TeraCloud) | venv(需 requests,已装) |

验证记录(2026-10-23):mp-upload 4 个文件全部上传+远端回读校验一致;
curl 全链路(换票据→GET update.json→HEAD APK)通,sha256 与 update.json 一致。
mp-push SHA 校验过、旧 mp-share resolve 通(飞牛链路已不再使用)。

## 本地构建环境(2026-10-11,便携式,全在 C:/Users/kangw/.workbuddy/)

| 组件 | 位置 | 说明 |
|---|---|---|
| JDK 11 (Temurin) | binaries/java/jdk-11.0.32.1+1 | AGP 7.4.2 要求 JDK 11+ |
| Gradle 7.5 | binaries/gradle/gradle-7.5 | 与 CI 同版本 |
| Android SDK | android-sdk/ | platform-tools + platforms/android-30 + build-tools 30.0.0/30.0.3(见下) |
| 依赖缓存 | gradle-home/ | GRADLE_USER_HOME 隔离,不污染 C:/Users/kangw/.gradle |

入口:`mp-gradle assembleDebug`(已进 PATH;产物 app/build/outputs/apk/debug/)。
项目经 local.properties(已 gitignore)指向 SDK,迁移=拷 .workbuddy 四个目录+改 local.properties 一行。

坑:
- Google 已下架 windows 版 build-tools 30.0.3(r30.0.3-windows.zip 404),本机 build-tools/30.0.3 是
  30.0.0 的别名(source.properties 改了 Pkg.Revision)。AGP 7.4.2 强制最低 30.0.3,这是让本地构建过的最小 hack。
- 新版 cmdline-tools 要 JDK 17,没走 sdkmanager,三个组件直接从 dl.google.com/repository 下载手动布局。
- local.properties 的 sdk.dir 用正斜杠:Java Properties 会吞掉单反斜杠(`\U`→`U`),写成 C:/Users 会变成乱码路径,
  报"文件名、目录名或卷标语法不正确"。
- 下载源:JDK 走 adoptium API(需代理);cmdline-tools/SDK 组件 dl.google.com 直连即通;gradle 走腾讯镜像。
