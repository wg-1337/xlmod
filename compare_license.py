# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。
"""比对本地与远端的 license.json 是否为同一份授权：
   python compare_license.py <本地 license.json> <远端 license.json>
   退出码 0 = 一致（远端已生效）；1 = 不一致（多半是 CDN 缓存未刷新）。

V4.3p 的授权是密文（每次都换 salt/IV），因此按 **enc 密文** 比对：密文相同 = 同一份授权。
"""
import hashlib
import io
import json
import sys


def fingerprint(path):
    o = json.load(io.open(path, encoding='utf-8'))
    if 'enc' in o:
        blob = (o.get('salt', '') + '|' + o['enc']).encode('ascii')
        return hashlib.sha256(blob).hexdigest(), len(o['enc']), '加密授权'
    import base64
    raw = base64.b64decode(o['payload'])
    return hashlib.sha256(raw).hexdigest(), len(raw), '明文授权'


local = sys.argv[1] if len(sys.argv) > 1 else 'license.json'
remote = sys.argv[2] if len(sys.argv) > 2 else 'license.json'
la, ln, lf = fingerprint(local)
ra, rn, rf = fingerprint(remote)
print('  本地授权 sha256: %s (%d 字节, %s)' % (la, ln, lf))
print('  远端授权 sha256: %s (%d 字节, %s)' % (ra, rn, rf))
if la == ra:
    print('  => 一致：远端已是最新授权（端上最长 60 秒内生效）')
    sys.exit(0)
print('  => 不一致：远端还是旧版本（CDN 缓存一般 5 分钟内刷新；端上仍是旧授权）')
sys.exit(1)
