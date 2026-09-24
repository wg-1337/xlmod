# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""简易 MP4 盒解析：提取关键元数据用于对比压缩前后视频。"""
import struct
import sys


def read_u32(b, o):
    return struct.unpack_from(">I", b, o)[0]


def read_u16(b, o):
    return struct.unpack_from(">H", b, o)[0]


def parse_boxes(data, start, end, depth=0):
    out = []
    off = start
    while off + 8 <= end:
        size = read_u32(data, off)
        typ = data[off + 4:off + 8].decode("latin1")
        hdr = 8
        if size == 1:
            size = struct.unpack_from(">Q", data, off + 8)[0]
            hdr = 16
        elif size == 0:
            size = end - off
        out.append((off, hdr, size, typ, depth))
        if typ in ("moov", "trak", "mdia", "minf", "stbl", "udta", "meta", "ilst", "edts", "mvex", "dinf", "mfra"):
            inner_start = off + hdr
            if typ == "meta":
                inner_start += 4  # fullbox version/flags
            out += parse_boxes(data, inner_start, off + size, depth + 1)
        off += size
    return out


def full_u32(data, o):
    return struct.unpack_from(">I", data, o)[0]


def hexdump_line(data, o, n=16):
    return data[o:o + n].hex(" ")


def main():
    for path in sys.argv[1:]:
        with open(path, "rb") as f:
            data = f.read()
        print("=" * 70)
        print("FILE:", path, "size=%d bytes (%.2f MB)" % (len(data), len(data) / 1048576.0))
        print("first 64 bytes:", hexdump_line(data, 0, 64))
        boxes = parse_boxes(data, 0, len(data))
        types = set()
        for off, hdr, size, typ, dep in boxes:
            types.add(typ)
            if dep <= 1:
                print("  box @%08x %s size=%d depth=%d" % (off, typ, size, dep))
        # ftyp
        for off, hdr, size, typ, dep in boxes:
            if typ == "ftyp":
                major = data[off + hdr:off + hdr + 4].decode("latin1")
                minor = read_u32(data, off + hdr + 4)
                compats = [data[off + hdr + 8 + i * 4:off + hdr + 12 + i * 4].decode("latin1") for i in range((size - hdr - 8) // 4)]
                print("  ftyp: major=%s minor=%d compats=%s" % (major, minor, compats))
        # mvhd
        for off, hdr, size, typ, dep in boxes:
            if typ == "mvhd":
                ver = data[off + hdr]
                if ver == 0:
                    ts = full_u32(data, off + hdr + 12)
                    dur = full_u32(data, off + hdr + 16)
                else:
                    ts = struct.unpack_from(">I", data, off + hdr + 20)[0]
                    dur = struct.unpack_from(">Q", data, off + hdr + 24)[0]
                print("  mvhd: ver=%d timescale=%d duration=%d (%.2fs)" % (ver, ts, dur, dur / ts if ts else 0))
        # tkhd / mdhd / stsd / stsz per track
        for off, hdr, size, typ, dep in boxes:
            if typ == "tkhd":
                ver = data[off + hdr]
                if ver == 0:
                    dur = full_u32(data, off + hdr + 16)
                    w = struct.unpack_from(">I", data, off + hdr + 76)[0]
                    h = struct.unpack_from(">I", data, off + hdr + 80)[0]
                else:
                    dur = struct.unpack_from(">Q", data, off + hdr + 24)[0]
                    w = struct.unpack_from(">I", data, off + hdr + 96)[0]
                    h = struct.unpack_from(">I", data, off + hdr + 100)[0]
                print("  tkhd: dur=%d width=%.2f height=%.2f" % (dur, w / 65536.0, h / 65536.0))
            if typ == "mdhd":
                ver = data[off + hdr]
                if ver == 0:
                    ts = full_u32(data, off + hdr + 12)
                    dur = full_u32(data, off + hdr + 16)
                else:
                    ts = struct.unpack_from(">I", data, off + hdr + 20)[0]
                    dur = struct.unpack_from(">Q", data, off + hdr + 24)[0]
                print("  mdhd: ver=%d timescale=%d duration=%d (%.2fs)" % (ver, ts, dur, dur / ts if ts else 0))
            if typ == "stsd":
                entry_count = full_u32(data, off + hdr + 4)
                print("  stsd: entries=%d" % entry_count)
                pos = off + hdr + 8
                for e in range(entry_count):
                    esize = read_u32(data, pos)
                    etype = data[pos + 4:pos + 8].decode("latin1")
                    print("    entry[%d] type=%s size=%d" % (e, etype, esize))
                    if etype == "avc1":
                        w = read_u16(data, pos + 24 + 8)
                        h = read_u16(data, pos + 24 + 10)
                        # avcC at offset 78 within avc1 sample entry
                        avcc = pos + 78
                        prof = data[avcc + 1]
                        lev = data[avcc + 3]
                        print("      avc1: %dx%d avcC profile=%d level=%d" % (w, h, prof, lev))
                    if etype == "hvc1" or etype == "hev1":
                        w = read_u16(data, pos + 24 + 8)
                        h = read_u16(data, pos + 24 + 10)
                        print("      %s: %dx%d (HEVC)" % (etype, w, h))
                    if etype == "mp4a":
                        print("      mp4a: audio")
                    pos += esize
            if typ == "stsz":
                sample_size = full_u32(data, off + hdr + 4)
                count = full_u32(data, off + hdr + 8)
                total = sample_size * count
                if sample_size == 0:
                    total = sum(full_u32(data, off + hdr + 12 + i * 4) for i in range(count))
                print("  stsz: sample_size=%d count=%d total_bytes=%d" % (sample_size, count, total))
        # 搜索已知编码器/服务器标记
        for marker in [b"Lavf", b"Lavc", b"FFmpeg", b"HandBrake", b"isom", b"mp42", b"qt  ", b"encoder"]:
            idx = data.find(marker)
            if idx >= 0:
                print("  marker '%s' at 0x%x (%s)" % (marker.decode(), idx, data[max(0, idx - 24):idx + 24].hex(" ")))
        # 搜索字符串形式的编码器标记
        idx = data.find(b"encoder")
        while idx >= 0 and idx < len(data):
            chunk = data[idx:idx + 64]
            txt = "".join(chr(c) for c in chunk if 32 <= c < 127)
            print("  'encoder' context: %r" % txt[:60])
            idx = data.find(b"encoder", idx + 1)
            break


if __name__ == "__main__":
    main()
