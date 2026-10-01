# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。
"""打印 license.json（单文件授权）摘要：python show_license.py [文件]

license.json = {"payload": base64(明文配置), "sig": base64(作者私钥签名)}
明文配置里除功能开关/公告外，还有管理员密码字段（admin 校验块；密码明文只在本地 features.json）。
"""
import io
import json
import sys

import sign_config as sc


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else 'license.json'
    payload, sig = sc.payload_of(path)
    cfg = json.loads(payload.decode('utf-8'))
    print('  单文件授权: %s（payload %d 字节 + 签名 %d 字节）' % (path, len(payload), len(sig)))
    print('  version : %s' % cfg.get('version'))
    print('  kill    : %s' % cfg.get('kill'))
    print('  有效期  : %s' % (cfg.get('expires') or '长期'))
    print('  公告    : %s' % (cfg.get('notice') or '(无)'))
    print('  开启项  : %s' % [k for k, v in (cfg.get('features') or {}).items() if v])
    print('  关闭项  : %s' % [k for k, v in (cfg.get('features') or {}).items() if not v])
    adm = cfg.get('admin') or {}
    if adm.get('hash'):
        print('  管理员  : PBKDF2-HMAC-SHA256 · %d 次迭代 · 盐 %s…（无密码明文）'
              % (adm.get('iters', 0), (adm.get('salt') or '')[:12]))
    elif cfg.get('admin_password'):
        print('  管理员  : 明文 admin_password（建议改用 set-pw + bundle 走校验块）')
    else:
        print('  管理员  : (无：端上不能解锁管理员模式)')


if __name__ == '__main__':
    main()
