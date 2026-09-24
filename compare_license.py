# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。
"""比对本地与远端的 license.json 是否为同一份配置：
   python compare_license.py <本地 license.json> <远端 license.json>
   退出码 0 = 一致（远端已生效）；1 = 不一致（多半是 CDN 缓存未刷新）。"""
import base64
import hashlib
import io
import json
import sys

def payload_sha(path):
    o = json.load(io.open(path, encoding='utf-8'))
    raw = base64.b64decode(o['payload'])
    return hashlib.sha256(raw).hexdigest(), len(raw)

local = sys.argv[1] if len(sys.argv) > 1 else 'license.json'
remote = sys.argv[2] if len(sys.argv) > 2 else 'license.json'
la, ln = payload_sha(local)
ra, rn = payload_sha(remote)
print('  本地配置 sha256: %s (%d 字节)' % (la, ln))
print('  远端配置 sha256: %s (%d 字节)' % (ra, rn))
if la == ra:
    print('  => 一致：远端已是最新授权（端上最长 60 秒内生效）')
    sys.exit(0)
print('  => 不一致：远端还是旧版本（CDN 缓存一般 5 分钟内刷新；端上仍是旧授权）')
sys.exit(1)
