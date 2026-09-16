# 飞牛音乐(FN Music)API 文档

来源两部分：
1. 静态逆向 `参考app/FN_Music_1.0.1_16309111824.apk`（Flutter + Go）
2. **实测 NAS 上的 Web 前端源码 + 真实接口调用**（已全部验证通过）

> 实测环境：`http://<NAS>:5666`，服务端 `mediasrvVersion 0.8.41`，音乐应用 `1.0.1`
> 曲库规模：814 首 / 203 张专辑

---

## 一、核心事实（实测）

### 1. Base URL

```
{服务器地址}/music/api/v1
```

**必须有 `/v1`**。少了 `/v1` 的请求会被 nginx 回落成前端 SPA 的 `index.html`（HTTP 200 返回 HTML，很容易误判为"接口正常但数据空"）。这是踩的第一个坑。

### 2. 请求签名头 authx

每个请求都要带：

```
authx: nonce=<6位随机数>&timestamp=<毫秒时间戳>&sign=<MD5>
```

sign 计算方式：

```
sign = MD5( SALT _ path _ nonce _ timestamp _ MD5(body) _ apiKey )
```

- `SALT` = `NDzZTVxnRKP8Z0jXg1VAMonaG8akvh`（写死在客户端里的常量）
- `path` = 不含 query 的路径，如 `/music/api/v1/track/list`
- `body`：GET 用「按 key 排序后的 query 串」，POST 用「JSON 原文」；为空时取 `MD5("")`
- `apiKey` 留空

> 实测：服务端目前**不校验** sign（故意传错也照常返回）。但按规范生成，将来校验也不会挂。

### 3. 登录

```
POST /music/api/v1/user/password-login
Content-Type: application/json

{"username":"kwj","password":"<SHA256(明文密码)>","deviceId":"<32位十六进制>"}
```

两个关键点：

- **密码必须传 SHA256 十六进制**，传明文返回 `code=100001 unknown error`
- **deviceId 必填**，缺失同样报 `100001`。格式是 32 位十六进制（客户端用 `crypto.randomUUID()` 去掉横线）

响应：

```json
{"code":0,"msg":"","data":{"userToken":"828e40c519a6485ab6af4b83df64e6b3","user":{"guid":"...","name":"kwj","role":"member"}}}
```

### 4. 鉴权

后续请求带 **Cookie**：

```
Cookie: music-token=<userToken>
```

也可以不带 Cookie，改用请求头 `Authorization: <userToken>`（**裸 token，不要加 `Bearer ` 前缀，加了会被拒**）。

> ⚠️ **媒体地址（播放流、封面）不支持 URL 参数传 token**。实测 `?token=xxx` 返回 401，
> 必须用 `Authorization` 头。这直接决定了播放器要用带 headers 的 `setDataSource` 重载。

### 5. 统一响应包装

```json
{"code":0,"msg":"","data":{ ... }}
```

常见错误码：

| code | 含义 |
|---|---|
| 0 | 成功 |
| 99999 | INVALID TOKEN（未登录 / token 失效），HTTP 401 |
| 100001 | unknown error（登录时多为密码格式不对或缺 deviceId） |
| 100002 | invalid arguments（参数名不对） |
| 120001 | unauthorized, please login again |

---

## 二、接口清单与参数

| 接口 | 方法 | 参数 | 说明 |
|---|---|---|---|
| `/user/password-login` | POST | username, password(sha256), deviceId | 登录 |
| `/track/list` | GET | `size` | 曲目列表，返回 `{list,total,sort}` |
| `/album/list` | GET | `size` | 专辑列表 |
| `/track/album-detail/list` | GET | `albumGUID`, `size` | 专辑内曲目 |
| `/search/track` | GET | `q`, `size` | 搜索（**参数是 `q`，不是 query**） |
| `/favorite-track/list` | GET | `size` | 收藏 |
| `/lyric/list` | GET | `trackGUID` | 歌词，返回 LRC 文本 |
| `/track/stream` | GET | `guid` | 播放流，audio/mpeg，支持 Range |
| `/static/cover` | GET | `coverId` | 封面，**注意不是 `/static/cover/track`** |

### 分页：只能靠 size 放大

**`offset` 实测无效**（传任何值都从第一条开始）。可用的做法：

1. 先 `size=1` 拿 `total`
2. 再 `size=min(total, 5000)` 一次拉全

实测 814 首一次取完没问题。

### 参数名规律

实体 ID 参数统一是「实体名 + 大写 GUID」：`trackGUID`、`albumGUID`。
写成 `guid`、`trackGuid`、`albumId` 都会返回 `100002 invalid arguments`。

---

## 三、数据结构

### 曲目（track/list 的 list 元素）

```json
{
  "guid": "86c3357f668b4686890f10c673819172",
  "title": "风催雨",
  "coverId": "album_30972689b750477eb2e0a8594ab7f5ca",
  "year": null, "discNo": 1, "trackNo": 1, "isrc": "HKG731775386",
  "duration": 178104,
  "isCue": false,
  "album": { "guid": "...", "name": "风催雨", "coverId": "album_xxx" },
  "artists": [ { "guid": "...", "name": "灼夭", "coverId": "artist_xxx" } ],
  "genres": [],
  "audioSpec": {
    "bitDepth": 32, "sampleRate": 44100, "channel": 2,
    "bitrate": 128000,
    "codec": "mp3", "format": "mp3",
    "path": "/vol2/1001/音乐/灼夭/01. 风催雨.mp3",
    "size": 3034456, "duration": 178104
  },
  "isFavorite": true
}
```

注：`duration` 单位毫秒；`bitrate` 单位 bps（项目里按 kbps 用，需 /1000）。

### 专辑（album/list 的 list 元素）

```json
{"guid":"...","name":"风催雨","coverId":"album_xxx","releaseDate":"2022-01-28",
 "barcode":"4894894173232","artists":[{"guid":"...","name":"灼夭"}],"trackCount":1}
```

曲目数字段叫 **`trackCount`**（不是 `songCount`）。

### 歌词

```
GET /lyric/list?trackGUID=xxx
→ data.list[0].content = "[00:00.00]作词 : 十满\n[00:01.00]作曲 : 南铃子\n..."
```

返回的是 **LRC 纯文本**，不是结构化数组，需要自己解析（项目里的 `LrcParser.parseLrcText` 直接可用）。

---

## 四、其余端点（静态逆向得到，未逐个实测）

从 Web 前端的端点字典完整提取，共 82 个：

```
/album/{list,detail,artist-detail/list}
/artist/{create,detail,list,list-all}
/genre/{create,detail,list}
/playlist/{list,detail,batch-detail,create,edit,delete,add-track,remove-track,purge-track,purge-track-count}
/favorite-track/{create,delete,list,purge-track,purge-track-count}
/play-history/{list,delete}
/search/{album,artist,playlist,track,suggest,index/rebuild}
/shared-library/{list,detail,create,edit,delete,scan,scan-all}
/track/{list,metadata,stream,transcode,transcode/heartbeat,transcode/quit,
        roam-start,roam-next,roam-previous,album-detail/list,artist-detail/list,
        genre-detail/list,playlist-detail/list,hls/:guid/preset.m3u8}
/user/{auth-login,password-login,logout,me,create,edit,delete,exists,list,passwd-change,unbanned}
/lyric/list
/initialization/{state,prepare,confirm}
/settings/{server,user}
/sys/config
/task/{list,cancel,delete,retry}
/event/report
/static/cover, /static/cover/track, /static/cover/playlist
/app-center/authed-dir/{list,sub/list}
```

`/sys/config` 可匿名访问，返回：

```json
{"nasOAuth":{"clientId":"EDDLUH2WLY"},"serverGUID":"...","serverName":"huilong",
 "serverVersion":"1.0.1","mediasrvVersion":"0.8.41"}
```

---

## 五、代码接入实现

| 文件 | 作用 |
|---|---|
| `MusicSourceApi.java` | 数据源统一接口（含 `getAuthHeaders()`） |
| `FnMusicApi.java` | 飞牛实现，按上面实测结论编写 |
| `MusicSourceFactory.java` | 按 `server_type` 创建实现 |
| `NavidromeApi.java` | 改为 `implements MusicSourceApi`，逻辑未动 |
| `NavidromeConfig.java` | 新增 `server_type` / `createSource()` |
| `MusicDataHolder.java` | 持有 `MusicSourceApi` |
| `ServerSettingsActivity` + 布局 | 「Navidrome / 飞牛音乐」单选 |
| `MusicService` | 网络播放时附加 `getAuthHeaders()` |
| `CoverLoader` | 封面下载时附加 `getAuthHeaders()` |

关键点：**播放必须走 `setDataSource(Context, Uri, Map headers)`**。
飞牛的流地址不含凭据，用 `setDataSource(String url)` 会 401。

---

## 六、FN ID 接入（**已打通**，见 6.5；6.1–6.4 为早期结论，已部分推翻）

> **更正说明**：早期判断「FN ID 做不到」是错的。后来从飞牛官方的 FN Connect Web 前端
> （`https://static2.fnnas.com/connect/`）挖到了**公开的 FN ID 解析接口**，
> 它直接返回 NAS 的内网 IP / 公网 IP / IPv6 / DDNS / 中继地址。
> 已实现为 `FnIdResolver.java`。6.1–6.4 保留作为背景，6.5 才是最终结论。

### 6.1 早期结论（已推翻）

~~只填一个 FN ID 就能连上，这条路在第三方 App 里走不通。~~
FN ID 确实不是域名，但飞牛提供了把它解析成地址的公开 API，所以**可以做**。

### 6.2 证据

**1) FN Connect = frp 改造的私有穿透协议（Go，闭源）**

从 `libgojni.so` 提取到的符号：

```
trimcon-sdk/client.(*Control).connectServer
trimcon-sdk/client.(*Control).handleNatHoleResp      ← NAT 打洞
trimcon-sdk/client.(*Control).handleReqWorkConn
trimcon-sdk/client.(*Control).heartbeatWorker
trimcon-sdk/client/visitor.(*QUICTunnelSession).OpenConn   ← QUIC 隧道
trimcon-sdk/client/visitor.(*TPPVisitor).Run               ← 私有 TPP 协议
trimcon-sdk/client/visitor.(*Manager).TransferConn
```

即：P2P 打洞 + QUIC 隧道 + 自定义 TPP，外加云端登录与设备发现。
没有 Go 侧 SDK，Java 无法复刻。

**2) 不存在任何 HTTP 中继域名**

用 DoH（alidns 权威查询）实测，均为 **NXDOMAIN（Status=3）**：

| 域名 | 结果 |
|---|---|
| `check.fnnas.cn` | NXDOMAIN（APK 内硬编码，已废弃） |
| `fnofly.com` | NXDOMAIN（根域都不存在） |
| `<随机12位串>.fnnas.com` | NXDOMAIN —— **无通配解析** |
| `huilong.fnnas.com` | NXDOMAIN |
| `www.fnnas.com` / `fnnas.cn` | 存在（官网，阿里深圳 ALB） |
| `fnos.net` / `share.fnnas.net` | 存在（分享服务） |

网上流传的 `https://<FNID>.fnofly.com`、`xxx.fnnas.com` 均为不实内容，实测无法解析。

**3) 官方行为佐证**

- APK 内登录相关字符串：`FnConnectLoginType.`、`LoginConnectType.`、`LoginServerType.`、
  `login_input_address_error`、`login_input_port_invalid`、`music_login_nas_auth`。
  说明官方 App 也是「地址+端口」与「FN Connect」两套登录方式并存。
- 飞牛帮助中心原文：**「FN ID 无需填写端口号」** —— 正说明它走隧道而非域名。
- 官方同时声明：FN Connect **中继转发会因流量成本限速**。

### 6.5 真正的解法：FN ID 解析接口（已实测通过）

飞牛官方 FN Connect 的 Web 前端托管在 `https://static2.fnnas.com/connect/`，
（`5ddd.com` 与 `fnos.net` 均为飞牛官方域名，同属广州铁刃智造，共用阿里云 GA 实例。）

**解析接口**

```
POST https://fnos.net/api/v1/fn/con        (http://check.fnos.net 亦可)
Content-Type: application/json
authx:   nonce=<6位>&timestamp=<ms>&sign=<md5>
fn-sign: <sha256>
Body:    {"fnId":"<FN ID>"}
```

签名（与音乐 API 同一套 authx 结构，**但 apiKey 不同**）：

```
authx.sign = MD5( PREFIX _ "/api/v1/fn/con" _ nonce _ timestamp _ MD5(body) _ APIKEY )
PREFIX = NDzZTVxnRKP8Z0jXg1VAMonaG8akvh            ← 与音乐 API 相同
APIKEY = zIGtkc3dqZnJpd29qZXJqa2w7c                ← 音乐 API 此处为空串
fn-sign = SHA256( "trim_connect`" + fnId + "`" + timestamp + "`anna" )
```

**返回示例（FN ID = k495378412，实测）**

```json
{"code":0,"data":{
  "ipv4":["192.168.2.250"],
  "ipv6":[],
  "publicIpv4":["117.148.115.3"],
  "publicIpv6":["2409:8a28:5863:f8a4:92c:6301:a3d3:c7e5","2409:8a28:5863:f8a4::ae4"],
  "ddns":null,
  "fn":["k495378412.5ddd.com:443"],
  "port":{"httpPort":5666,"httpsPort":5667},
  "checkSum":"31484","ver":"3.0.0"
}}
```

**地址拼装规则（复刻自前端 `genUrl`）**

| 来源 | 拼法 |
|---|---|
| `ipv4`（内网） | `http://<ip>:<httpPort>` |
| `ddns` | `https://<域名>:<httpsPort>` |
| `publicIpv4` | `http://<ip>:<httpPort>` |
| `publicIpv6` | `http://[<ipv6>]:<httpPort>` |
| `fn`（中继） | `https://<addr>`（自带端口，不再追加） |

**推荐探测顺序**：内网 → DDNS → 公网 IPv4 → 公网 IPv6 → 中继。

**可达性判别（关键）**
GET `{base}/music/api/v1/initialization/state`，必须满足：
1. HTTP 200
2. 响应体能解析为 JSON
3. `code == 0`

不满足即视为不可用。**这条能挡掉被 nginx SPA 兜底的情况** —— 中继未代理时返回的是
HTML 检测页，JSON 解析直接失败，不会被误判为可用。

### 6.6 中继（`*.5ddd.com`）的行为

判别方法：请求 `/static/bridge.html`。
- 真 NAS 返回真实的 bridge 页面
- 中继未代理时返回 FN Connect 的 SPA 检测页

实测 `k495378412.5ddd.com` 返回 SPA，**说明当前未代理到 NAS**（NAS 离线或 FN Connect 未连接）。
中继也不是纯 HTTP 反代：NAS 需先通过 trimcon 注册隧道，nginx 才有转发目标。

### 6.7 已实现

`FnIdResolver.java`
- `resolve(fnId)` → 候选地址列表（按推荐顺序）
- `pickReachable(list)` → 逐个探测，返回第一个可用的 base URL，全不通返回 `null`
- `probe(baseUrl)` → 单个地址探测

解析失败 / FN ID 不存在时返回空列表，不抛异常。FN ID 无效时服务端返回
`{"code":3000037,"msg":"Not Found Error"}`。

### 6.3 局域网 mDNS 自动发现（备选，适合车机）

APK 内挖到官方的局域网发现实现，走的是标准 mDNS：

```
_trim_music._tcp            ← 服务类型
startDiscovery / stopDiscovery
onServiceDiscovered / onDiscoveryStartFailed
discoveryToken
lan discovery services is empty!!!
```

Android 原生 `NsdManager` 可直接发现，服务类型填 `_trim_music._tcp`。

**车机场景的推荐连接策略（双地址自动回退）**

1. 启动 → mDNS 扫描 `_trim_music._tcp`，拿到 NAS 内网 IP
2. 命中 → 用内网 IP 直连（延迟最低、不耗流量）
3. 未命中（车在外）→ 回退到已保存的外网域名
4. 每 N 秒探测一次，网络切换时重新选路

### 6.8 对本项目的建议

FN ID 解析已可用，但**能否连上取决于 NAS 侧的网络配置**：

| 条件 | 结果 |
|---|---|
| NAS 有公网 IPv4 + 路由器做了 5666 端口映射 | ✅ 直连，最快 |
| NAS 有公网 IPv6 + 防火墙放行 5666 | ✅ 直连（国内家宽常见，值得优先试） |
| 配置了 DDNS | ✅ 直连 |
| 只能走 FN Connect 中继 | ⚠️ 依赖 NAS 在线且隧道已建立，官方还会限速 |

排查顺序：先试 IPv6，再在路由器上做 5666 端口映射到 NAS 内网 IP，最后才考虑中继。

---

## 七、安全提醒

- 登录密码以 SHA256 传输（等同明文暴露在链路上），仅限内网或 HTTPS 使用。
- 把 NAS 的 5666 端口直接暴露公网，意味着管理面可被全网扫描。建议改端口 / 加访问控制 / 走 VPN 或内网穿透。
