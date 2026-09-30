# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。
"""打印 license.json（单文件授权）摘要：python show_license.py [文件]

支持两种格式：
  · V4.3p 加密授权 {"v":2,"salt","enc","sig"}（本机有主密钥才能看到明文，否则只显示"密文"信息）
  · 旧版明文授权 {"payload","sig"}
"""
import io
import json
import sys

import sign_config as sc


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else 'license.json'
    o = json.load(io.open(path, encoding='utf-8'))
    if 'enc' in o:
        print('  格式    : 加密授权 V%s（%s）' % (o.get('v'), o.get('alg')))
        print('  密文长度: %d 字符' % len(o.get('enc', '')))
        master = sc.load_master(required=False)
        if master is None:
            print('  明文    : 本机没有主密钥 keys/license_key.txt，无法解密（这正是"仓库只有密文"的效果）')
            return
        blob = sc._b64d(o['enc'])
        raw = sc._openssl_dec(sc.derive_key(master, sc._b64d(o['salt'])), blob[:16], blob[16:])
        cfg = json.loads(raw.decode('utf-8'))
    else:
        print('  格式    : 明文授权（旧版；建议改用 python sign_config.py seal）')
        cfg = json.loads(sc._b64d(o['payload']).decode('utf-8'))
    print('  version : %s' % cfg.get('version'))
    print('  kill    : %s' % cfg.get('kill'))
    print('  有效期  : %s' % (cfg.get('expires') or '长期'))
    print('  公告    : %s' % (cfg.get('notice') or '(无)'))
    print('  开启项  : %s' % [k for k, v in (cfg.get('features') or {}).items() if v])
    print('  关闭项  : %s' % [k for k, v in (cfg.get('features') or {}).items() if not v])
    adm = cfg.get('admin') or {}
    print('  管理员块: %s' % ('%s · %d 次迭代 · 盐 %s' % (adm.get('alg'), adm.get('iters', 0),
                                                          (adm.get('salt') or '')[:12] + '…')
                              if adm.get('hash') else '(无：端上不能解锁管理员模式)'))


if __name__ == '__main__':
    main()
