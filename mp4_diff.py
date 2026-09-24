# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MP4 关键参数逐字段对比（压缩前 vs 压缩后），用于确定"头部伪装"该写哪些值。

输出：ftyp / mvhd / 每个 trak 的 tkhd(宽高) / mdhd(timescale) / stsd 条目（avc1/avcC/btrt/pasp/colr/mp4a）
      / stsz(样本数与总字节→码率) / SPS 解析出的真实编码分辨率。
"""
import struct
import sys


def u32(b, o):
    return struct.unpack_from('>I', b, o)[0]


def u16(b, o):
    return struct.unpack_from('>H', b, o)[0]


def u64(b, o):
    return struct.unpack_from('>Q', b, o)[0]


CONTAINERS = ('moov', 'trak', 'mdia', 'minf', 'stbl', 'udta', 'edts', 'mvex', 'dinf')


def boxes(b, start, end, depth=0):
    out = []
    off = start
    while off + 8 <= end:
        size = u32(b, off)
        typ = b[off + 4:off + 8].decode('latin1')
        hdr = 8
        if size == 1:
            size = u64(b, off + 8)
            hdr = 16
        elif size == 0:
            size = end - off
        if size < hdr:
            break
        out.append((off, hdr, size, typ, depth))
        if typ in CONTAINERS:
            out += boxes(b, off + hdr, off + size, depth + 1)
        elif typ == 'stsd':
            entry_start = off + hdr + 8
            e = entry_start
            entry_count = u32(b, off + hdr + 4)
            for _ in range(entry_count):
                if e + 8 > off + size:
                    break
                esize = u32(b, e)
                etyp = b[e + 4:e + 8].decode('latin1')
                out.append((e, 8, esize, etyp, depth + 1))
                out += boxes(b, e + 8 + 78 if etyp in ('avc1', 'hvc1', 'hev1') else e + 8 + 28 if etyp == 'mp4a' else e + 8,
                             e + esize, depth + 2)
                e += esize
        off += size
    return out


def find(bl, typ, depth=None):
    return [x for x in bl if x[3] == typ and (depth is None or x[4] == depth)]


def parse_sps(sps):
    """极简 H.264 SPS 解析：返回 (profile, level, 编码宽, 编码高, 帧数相关)"""
    bits = []
    for byte in sps:
        for i in range(7, -1, -1):
            bits.append((byte >> i) & 1)

    def read(n):
        nonlocal pos
        v = 0
        for _ in range(n):
            v = (v << 1) | bits[pos]
            pos += 1
        return v

    def ue():
        nonlocal pos
        zeros = 0
        while pos < len(bits) and bits[pos] == 0:
            zeros += 1
            pos += 1
        pos += 1
        return (1 << zeros) - 1 + (read(zeros) if zeros else 0)

    def se():
        k = ue()
        return (k + 1) // 2 if k % 2 else -(k // 2)

    pos = 0
    profile = read(8)
    read(8)                      # constraint flags
    level = read(8)
    ue()                         # seq_parameter_set_id
    chroma = 1
    if profile in (100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135):
        chroma = ue()
        if chroma == 3:
            read(1)
        ue(); ue(); read(1)
        if read(1):
            n = 8 if chroma != 3 else 12
            for i in range(n):
                if read(1):
                    skip = 16 if i < 6 else 64
                    last = 8
                    nxt = 8
                    for _ in range(skip // 8 if False else 0):
                        pass
                    # scaling list
                    size = 16 if i < 6 else 64
                    j = 0
                    while j < size:
                        if nxt != 0:
                            nxt = (last + se() + 256) % 256
                        last = nxt if nxt != 0 else last
                        j += 1
    ue()                         # log2_max_frame_num_minus4
    poc = ue()
    if poc == 0:
        ue()
    elif poc == 1:
        read(1); se(); se()
        for _ in range(ue()):
            se()
    ue()                         # max_num_ref_frames
    read(1)                      # gaps_in_frame_num
    w_mbs = ue() + 1
    h_map = ue() + 1
    frame_mbs_only = read(1)
    if not frame_mbs_only:
        read(1)
    read(1)                      # direct_8x8
    crop_l = crop_r = crop_t = crop_b = 0
    if read(1):
        crop_l, crop_r, crop_t, crop_b = ue(), ue(), ue(), ue()
    w = w_mbs * 16
    h = (2 - frame_mbs_only) * h_map * 16
    sub_w = 2 if chroma in (1, 2) else 1
    sub_h = 2 if chroma == 1 else 1
    w -= (crop_l + crop_r) * sub_w
    h -= (crop_t + crop_b) * sub_h
    return profile, level, w, h, frame_mbs_only


def dump(path):
    with open(path, 'rb') as f:
        b = f.read()
    bl = boxes(b, 0, len(b))
    print('=' * 78)
    print('FILE %s  size=%d (%.2f MB)' % (path, len(b), len(b) / 1048576.0))
    for off, hdr, size, typ, dep in find(bl, 'ftyp'):
        brands = b[off + 16:off + size].decode('latin1', 'replace')
        print('  ftyp  major=%s  compatible=%s' % (b[off + 8:off + 12].decode('latin1'), brands))
    for off, hdr, size, typ, dep in find(bl, 'mvhd'):
        ver = b[off + 8]
        ts = u32(b, off + 20) if ver == 0 else u32(b, off + 28)
        du = u32(b, off + 24) if ver == 0 else u64(b, off + 36)
        print('  mvhd  version=%d timescale=%d duration=%d (%.2fs)' % (ver, ts, du, du / float(ts or 1)))
    for ti, tr in enumerate([x for x in bl if x[3] == 'trak' and x[4] == 1]):
        toff, thdr, tsize = tr[0], tr[1], tr[2]
        sub = boxes(b, toff + thdr, toff + tsize)
        print('  --- trak #%d ---' % ti)
        for off, hdr, size, typ, dep in find(sub, 'tkhd'):
            ver = b[off + 8]
            w = u32(b, off + 84) / 65536.0 if ver == 0 else u32(b, off + 96) / 65536.0
            h = u32(b, off + 88) / 65536.0 if ver == 0 else u32(b, off + 100) / 65536.0
            print('    tkhd v%d  width=%.0f height=%.0f' % (ver, w, h))
        for off, hdr, size, typ, dep in find(sub, 'mdhd'):
            ver = b[off + 8]
            ts = u32(b, off + 20) if ver == 0 else u32(b, off + 28)
            du = u32(b, off + 24) if ver == 0 else u64(b, off + 36)
            print('    mdhd v%d  timescale=%d duration=%d (%.2fs)' % (ver, ts, du, du / float(ts or 1)))
        for off, hdr, size, typ, dep in find(sub, 'hdlr'):
            print('    hdlr  handler=%s' % b[off + 16:off + 20].decode('latin1'))
        for e in [x for x in sub if x[4] >= 3]:
            eoff, ehdr, esize, etyp = e[0], e[1], e[2], e[3]
            if etyp in ('avc1', 'hvc1', 'hev1'):
                w = u16(b, eoff + 32)
                h = u16(b, eoff + 34)
                print('    stsd  %s  width=%d height=%d' % (etyp, w, h))
                eb = boxes(b, eoff + 8 + 78, eoff + esize)
                for o2, h2, s2, t2, d2 in eb:
                    if t2 == 'avcC':
                        cfg = b[o2 + 8]
                        prof = b[o2 + 9]
                        compat = b[o2 + 10]
                        lvl = b[o2 + 11]
                        length_size = (b[o2 + 12] & 0x03) + 1
                        nss = b[o2 + 13] & 0x1F
                        p = o2 + 14
                        spss = []
                        for _ in range(nss):
                            if p + 2 > len(b):
                                break
                            ln = u16(b, p)
                            spss.append(b[p + 2:p + 2 + ln])
                            p += 2 + ln
                        print('      avcC  cfgVer=%d profile=%d compat=0x%02x level=%d lengthSize=%d numSPS=%d'
                              % (cfg, prof, compat, lvl, length_size, nss))
                        for s in spss:
                            try:
                                pr, lv, sw, sh, fmo = parse_sps(s[1:] if s and s[0] == 0x67 else s)
                                print('        SPS len=%d nal=0x%02x → profile=%d level=%d 编码分辨率=%dx%d frame_mbs_only=%d'
                                      % (len(s), s[0], pr, lv, sw, sh, fmo))
                            except Exception as ex:
                                print('        SPS len=%d (parse failed: %s) %s' % (len(s), ex, s[:12].hex()))
                    elif t2 == 'btrt':
                        print('      btrt  bufferSizeDB=%d maxBitrate=%d avgBitrate=%d'
                              % (u32(b, o2 + 8), u32(b, o2 + 12), u32(b, o2 + 16)))
                    elif t2 == 'pasp':
                        print('      pasp  hSpacing=%d vSpacing=%d' % (u32(b, o2 + 8), u32(b, o2 + 12)))
                    elif t2 == 'colr':
                        print('      colr  type=%s' % b[o2 + 8:o2 + 12].decode('latin1'))
                    elif t2 == 'pixi':
                        print('      pixi  present')
            elif etyp == 'mp4a':
                ch = u16(b, eoff + 8 + 16)
                sr = u32(b, eoff + 8 + 24) >> 16
                print('    stsd  mp4a  channels=%d sampleRate=%d' % (ch, sr))
        for off, hdr, size, typ, dep in find(sub, 'stsz'):
            count = u32(b, off + 16)
            if u32(b, off + 12) == 0:
                total = sum(u32(b, off + 20 + 4 * i) for i in range(min(count, 200000)))
            else:
                total = u32(b, off + 12) * count
            mdhd = find(sub, 'mdhd')[0]
            ts = u32(b, mdhd[0] + 20)
            du = u32(b, mdhd[0] + 24)
            secs = du / float(ts or 1)
            print('    stsz  samples=%d total=%.2f MB → 平均码率≈%.0f kbps (时长%.2fs)'
                  % (count, total / 1048576.0, (total * 8 / 1000.0 / secs) if secs else 0, secs))
    extra = sorted({x[3] for x in bl} - {'ftyp', 'moov', 'trak', 'mdia', 'minf', 'stbl', 'tkhd', 'mdhd', 'hdlr',
                                         'stsd', 'avc1', 'hvc1', 'hev1', 'mp4a', 'avcC', 'btrt', 'stsz', 'stts',
                                         'stsc', 'stco', 'co64', 'vmhd', 'smhd', 'dinf', 'dref', 'url ', 'mvhd',
                                         'esds', 'pasp', 'colr', 'pixi', 'mdat', 'free', 'skip', 'udta', 'meta'})
    print('  其它 box:', extra)


if __name__ == '__main__':
    for p in sys.argv[1:]:
        try:
            dump(p)
        except Exception as e:
            print('FAILED %s: %s' % (p, e))
