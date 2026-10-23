#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把构建产物 + 车机自更新元数据推到 TeraCloud /dav/apk(公开分享目录)。

【为什么单独一个脚本,不复用 webdav_upload.py】
webdav_upload.py 依赖 cli/webdav_cred.py,后者在模块级就
`from ctypes import wintypes`(Windows DPAPI),在 GitHub Actions 的
ubuntu runner 上 import 直接失败。这个脚本刻意自包含、零 Windows 依赖,
凭据只从环境变量取,Linux / Windows 都能跑。

用法(由 .github/workflows/build.yml 调用):
    WEBDAV_USER=xxx WEBDAV_PASS=xxx python cli/teracloud_publish.py \\
        --apk app/build/outputs/apk/debug/app-debug.apk \\
        --channel debug \\
        --version-name 6.2.447-pre \\
        [--version-code 100413] \\
        --notes-file /tmp/release_notes.md

产出并上传两个文件:
    app-debug.apk    + update-debug.json   (channel=debug)
    app-release.apk  + update.json         (channel=release)

行为约定(重要):
  - **凭据没配(还没在 Actions secrets/vars 里填)→ 打印提示并 exit 0**,
    绝不让构建变红。等用户填好 secrets 后自动生效,不需要改脚本。
  - 凭据有但上传失败(密码错 / 网络错 / 回读大小不一致)→ exit 1 把构建搞红,
    因为这时"车机拿不到新包"是真故障,必须暴露出来。
  - 上车前会算 sha256 并写进 JSON,车机侧靠它校验完整性。

环境变量:
    WEBDAV_USER / WEBDAV_PASS  上传凭据(必填,缺失则跳过)
    WEBDAV_BASE                默认 https://yura.teracloud.jp/dav/apk
"""

import argparse
import base64
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("WEBDAV_BASE", "https://yura.teracloud.jp/dav/apk").rstrip("/")
TIMEOUT = 180
# 小于该字节数视为半截包,拒绝上传(防止把坏包传上去让车机拿到)
MIN_APK_BYTES = 100 * 1024

APK_NAME = {"debug": "app-debug.apk", "release": "app-release.apk"}
META_NAME = {"debug": "update-debug.json", "release": "update.json"}


def credentials():
    """从环境变量取凭据;任一项为空即视为"未配置"。"""
    user = os.environ.get("WEBDAV_USER", "").strip()
    pwd = os.environ.get("WEBDAV_PASS", "").strip()
    return user, pwd


def req(url, method, user, pwd, data=None, extra=None):
    r = urllib.request.Request(url, data=data, method=method)
    token = base64.b64encode(("%s:%s" % (user, pwd)).encode("utf-8")).decode("ascii")
    r.add_header("Authorization", "Basic " + token)
    if data is not None:
        r.add_header("Content-Type", "application/octet-stream")
    for k, v in (extra or {}).items():
        r.add_header(k, v)
    return r


def ensure_dir(user, pwd):
    """确保目标目录存在。已存在时 WebDAV 返回 405,视为正常。"""
    try:
        with urllib.request.urlopen(req(BASE + "/", "MKCOL", user, pwd),
                                    timeout=TIMEOUT) as r:
            return True, r.status
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 405, 409):
            return True, e.code
        return False, e.code
    except Exception as e:
        return False, str(e)


def put(name, path, user, pwd):
    """上传单个文件,并回读远端大小校验。"""
    local_size = os.path.getsize(path)
    with open(path, "rb") as f:
        data = f.read()
    try:
        with urllib.request.urlopen(
                req(BASE + "/" + name, "PUT", user, pwd, data=data),
                timeout=TIMEOUT) as r:
            status = r.status
    except urllib.error.HTTPError as e:
        return False, "HTTP %s" % e.code
    except Exception as e:
        return False, str(e)

    try:
        with urllib.request.urlopen(req(BASE + "/" + name, "HEAD", user, pwd),
                                    timeout=TIMEOUT) as r:
            cl = r.headers.get("Content-Length")
            back = int(cl) if cl else None
    except Exception:
        back = None
    if back is not None and back != local_size:
        return False, "远端 %d 字节 ≠ 本地 %d 字节" % (back, local_size)
    return True, "HTTP %s  %d 字节  远端校验一致" % (status, local_size)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def extract_notes(body):
    """取 release 正文里的『## 本次更新』小节,截断到 300 字,作为车机弹窗的更新说明。
    与 cli/publish_apk.py 的 extract_notes 保持同一套正则,两边行为一致。"""
    if not body:
        return ""
    m = re.search(r"##\s*本次更新\s*\n(.*?)(?:\n##|\Z)", body, re.S)
    text = (m.group(1) if m else body).strip()
    return text[:300]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True, help="本地 APK 路径")
    ap.add_argument("--channel", choices=["debug", "release"], required=True)
    ap.add_argument("--version-name", required=True, help="如 6.2.447-pre / 6.2")
    ap.add_argument("--version-code", type=int, default=None)
    ap.add_argument("--notes-file", default=None,
                    help="release 正本文件,从中抽取『## 本次更新』小节")
    ap.add_argument("--notes", default=None, help="直接给更新说明(优先于 --notes-file)")
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        print("::error::APK 不存在: %s" % args.apk)
        return 1

    user, pwd = credentials()
    if not user or not pwd:
        # 还没在 Actions secrets / variables 里配 → 跳过,不让构建变红。
        print("[跳过] 未配置 TeraCloud 上传凭据,本次不上传。")
        print("       在仓库 Settings → Secrets and variables → Actions 里填")
        print("       WEBDAV_USER / WEBDAV_PASS 后,下次构建自动生效。")
        return 0

    size = os.path.getsize(args.apk)
    if size < MIN_APK_BYTES:
        print("::error::APK 仅 %d 字节,疑似半截包,拒绝上传" % size)
        return 1

    ok, code = ensure_dir(user, pwd)
    if not ok:
        print("::error::TeraCloud 目录不可达: %s" % code)
        return 1
    print("目标目录可达: %s/ (HTTP %s)" % (BASE, code))

    if args.notes is not None:
        notes = args.notes.strip()
    elif args.notes_file and os.path.isfile(args.notes_file):
        with open(args.notes_file, "r", encoding="utf-8", errors="replace") as f:
            notes = extract_notes(f.read())
    else:
        notes = ""

    digest = sha256_file(args.apk)
    meta = {
        "versionName": args.version_name,
        "fileName": APK_NAME[args.channel],
        "sha256": digest,
    }
    if args.version_code:
        meta["versionCode"] = args.version_code
    meta["notes"] = notes

    meta_path = os.path.join(os.path.dirname(os.path.abspath(args.apk)),
                             META_NAME[args.channel])
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print("APK: %d 字节  sha256=%s" % (size, digest))
    print("元数据: %s" % json.dumps(meta, ensure_ascii=False))

    failed = False
    for remote, local in ((APK_NAME[args.channel], args.apk),
                          (META_NAME[args.channel], meta_path)):
        ok, msg = put(remote, local, user, pwd)
        print("[%s] %s  %s" % ("已上传" if ok else "失败", remote, msg))
        if not ok:
            failed = True

    if failed:
        print("::error::TeraCloud 上传失败,车机拿不到新包")
        return 1

    print("上传完成: %s + %s" % (APK_NAME[args.channel], META_NAME[args.channel]))
    print("车机通过公开分享链接匿名下载, APK 内不含任何密码。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
