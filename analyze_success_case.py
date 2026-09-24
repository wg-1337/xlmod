# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""分析"成功案例.mp4"的头尾伪装：
 - 打印首/尾各 96 字节（hex + ascii）
 - 试 XOR 0x5A（工程里 mp4_restore.py 用的键）看首尾能否还原成合法 MP4 结构
 - 对比 压缩前.mp4 / 压缩后.mp4 的结构与长度，判断是"哪种伪装"
"""
import io
import os
import struct
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
CASE = os.path.join(ROOT, '压缩视频示范', '成功案例.mp4')
ORIG = os.path.join(ROOT, '压缩视频示范', '压缩前.mp4')
COMP = os.path.join(ROOT, '压缩视频示范', '压缩后.mp4')
OUT = os.path.join(ROOT, 'evidence', 'analyze-success-case.txt')

log = []


def say(s=''):
    print(s)
    log.append(s)


def hx(b):
    return ' '.join('%02X' % c for c in b)


def asc(b):
    return ''.join(chr(c) if 32 <= c < 127 else '.' for c in b)


def show(tag, data, off, n=96):
    seg = data[off:off + n]
    say('  %-22s @%d: %s' % (tag, off, hx(seg)))
    say('  %-22s      %s' % ('', asc(seg)))


def boxes(data, start, end, depth=0, out=None, maxdepth=2):
    if out is None:
        out = []
    o = start
    while o + 8 <= end:
        size = struct.unpack_from('>I', data, o)[0]
        typ = data[o + 4:o + 8]
        if size < 8 or o + size > end:
            out.append((o, -1, typ.decode('latin1', 'replace'), depth, 'BAD_SIZE(%d)' % size))
            break
        out.append((o, size, typ.decode('latin1', 'replace'), depth, ''))
        if depth < maxdepth and typ in (b'moov', b'trak', b'mdia', b'minf', b'stbl', b'udta'):
            boxes(data, o + 8, o + size, depth + 1, out, maxdepth)
        o += size
    return out


def structure(tag, data):
    say('  [%s] 顶层结构:' % tag)
    top = boxes(data, 0, len(data), 0, [], 0)
    for o, size, typ, dep, note in top[:12]:
        say('    @%-10d %-6s size=%-10d %s' % (o, typ, size, note))
    return top


def try_xor(data, key):
    d = bytearray(data)
    for i in range(min(64, len(d))):
        d[i] ^= key
    for i in range(max(0, len(d) - 64), len(d)):
        d[i] ^= key
    return bytes(d)


def main():
    say('=' * 78)
    for p in (CASE, ORIG, COMP):
        if not os.path.exists(p):
            say('MISSING: %s' % p)
            continue
        size = os.path.getsize(p)
        say('FILE %s  size=%d (%.2f MB)' % (os.path.basename(p), size, size / 1048576.0))
    if not os.path.exists(CASE):
        say('成功案例.mp4 不存在，退出')
        return 1

    case = io.open(CASE, 'rb').read()
    say('')
    say('=== 1) 原始首尾字节 ===')
    show('head', case, 0)
    show('tail', case, len(case) - 96)
    say('')
    say('=== 2) 首尾各 64 字节 XOR 0x5A 后 ===')
    fixed = try_xor(case, 0x5A)
    show('head^x5A', fixed, 0)
    show('tail^x5A', fixed, len(fixed) - 96)
    say('')
    say('=== 3) 结构判定 ===')
    structure('原始', case)
    say('')
    structure('XOR 0x5A 还原后', fixed)
    say('')
    say('=== 4) 首尾差异定位（原始 vs 还原）===')
    diff = [i for i in range(len(case)) if case[i] != fixed[i]]
    say('  差异字节数 = %d' % len(diff))
    if diff:
        say('  差异区间 = %s' % ('连续头部 0..%d' % (diff[0]) if diff[-1] < 64 or diff[0] >= len(case) - 64
                                 else '%d..%d' % (diff[0], diff[-1])))
        say('  头部差异 = %d 字节，尾部差异 = %d 字节'
            % (len([i for i in diff if i < 64]), len([i for i in diff if i >= len(case) - 64])))
        # 检查是否"只改了少数几个字节"而不是整段 64 字节
        head_diff = [i for i in diff if i < 64]
        tail_diff = [i for i in diff if i >= len(case) - 64]
        if head_diff:
            runs = []
            s = head_diff[0]
            prev = head_diff[0]
            for i in head_diff[1:]:
                if i != prev + 1:
                    runs.append((s, prev))
                    s = i
                prev = i
            runs.append((s, prev))
            say('  头部差异区间: %s' % runs)
            say('  头部原值: %s' % hx(bytes(case[a:b + 1] for a, b in runs[:1] and [(runs[0][0], runs[0][1])] or [])))
        if tail_diff:
            say('  尾部差异前 16 个偏移（相对文件尾）: %s' % [len(case) - i - 1 for i in tail_diff[:16]])
    say('')
    say('=== 5) 与其它样本对比 ===')
    for p in (ORIG, COMP):
        if os.path.exists(p):
            d = io.open(p, 'rb').read()
            say('  %-14s size=%-10d 头8字节=%s' % (os.path.basename(p), len(d), hx(d[:8])))
    say('  成功案例        size=%-10d 头8字节=%s' % (len(case), hx(case[:8])))
    if os.path.exists(ORIG):
        o = io.open(ORIG, 'rb').read()
        if len(o) == len(case):
            same = sum(1 for i in range(len(o)) if o[i] != case[i])
            say('  ** 与 压缩前.mp4 长度相同；逐字节差异 = %d 字节 **' % same)
            if same:
                d = [i for i in range(len(o)) if o[i] != case[i]]
                say('     差异偏移范围: %d .. %d' % (d[0], d[-1]))
                say('     头部(0..64)差异 = %d，尾部(末 64)差异 = %d'
                    % (len([i for i in d if i < 64]), len([i for i in d if i >= len(o) - 64])))
                # 逐字节 XOR key 统计
                keys = {}
                for i in d:
                    keys[o[i] ^ case[i]] = keys.get(o[i] ^ case[i], 0) + 1
                say('     异或键统计（key: 次数）: %s' % sorted(keys.items(), key=lambda kv: -kv[1])[:6])
        else:
            say('  与 压缩前.mp4 长度不同（%d vs %d）' % (len(case), len(o)))
    io.open(OUT, 'w', encoding='utf-8').write('\n'.join(log) + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
