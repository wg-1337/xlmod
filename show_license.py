# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。
"""打印 license.json（单文件授权）里的配置摘要：python show_license.py [文件]"""
import base64, io, json, sys

path = sys.argv[1] if len(sys.argv) > 1 else 'license.json'
o = json.load(io.open(path, encoding='utf-8'))
cfg = json.loads(base64.b64decode(o['payload']).decode('utf-8'))
print('  version : %s' % cfg.get('version'))
print('  kill    : %s' % cfg.get('kill'))
print('  有效期  : %s' % (cfg.get('expires') or '长期'))
print('  公告    : %s' % (cfg.get('notice') or '(无)'))
print('  开启项  : %s' % [k for k, v in (cfg.get('features') or {}).items() if v])
