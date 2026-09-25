# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""mod 源码字符串加密：把 mod-src 的字符串字面量替换为 XLModHelper.de("<b64>")。

- 只处理 3 个 mod 源文件；注释不处理；Java 转义（\\ \" \n \t \uXXXX 等）先还原再加密。
- 输出到 obf-src/（保持包路径），不破坏 mod-src 原始源码。
- 与 XLModHelper.de() 的 XOR(0x5A)+Base64 对应。
"""
import base64
import os
import re
import shutil

SRC_DIR = os.path.join("mod-src", "net", "xuele", "xuelets", "mod")
DST_DIR = os.path.join("obf-src", "net", "xuele", "xuelets", "mod")
FILES = ["XLModConfig.java", "XLModFeatures.java", "XLModHelper.java", "XLModActivity.java", "XLModUpdate.java"]
# 多 dex 互锁：密钥由 Guard8/Guard9 运行期推导，构建期用 build_guard.py 实跑取值写入 guard-key.txt
def _load_key():
    try:
        with open("guard-key.txt") as f:
            return int(f.read().strip(), 0) & 0xFF
    except Exception:
        return 0x5A          # 兼容模式：无守卫 dex 时
KEY = _load_key()

def unescape(raw):
    """把 Java 字符串字面量内容还原为文本（含常见转义）。"""
    out = []
    i = 0
    n = len(raw)
    while i < n:
        c = raw[i]
        if c != "\\":
            out.append(c)
            i += 1
            continue
        i += 1
        if i >= n:
            out.append("\\")
            break
        e = raw[i]
        if e == "u" and i + 4 < n:
            out.append(chr(int(raw[i + 1:i + 5], 16)))
            i += 5
        elif e in "\\\"/'":
            out.append(e)
            i += 1
        elif e == "n":
            out.append("\n"); i += 1
        elif e == "r":
            out.append("\r"); i += 1
        elif e == "t":
            out.append("\t"); i += 1
        elif e == "b":
            out.append("\b"); i += 1
        elif e == "f":
            out.append("\f"); i += 1
        else:
            out.append(e)
            i += 1
    return "".join(out)


def encrypt(text):
    data = text.encode("utf-8")
    enc = bytes(b ^ KEY for b in data)
    return base64.b64encode(enc).decode("ascii")


def process(src_path, dst_path):
    with open(src_path, "r", encoding="utf-8") as f:
        text = f.read()
    out = []
    i = 0
    n = len(text)
    replaced = 0
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(text[i:j])
            i = j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(text[i:j])
            i = j
            continue
        if text[i] == '"':
            j = i + 1
            buf = []
            while j < n:
                c = text[j]
                if c == "\\":
                    buf.append(text[j])
                    if j + 1 < n:
                        buf.append(text[j + 1])
                        j += 2
                        continue
                    j += 1
                    continue
                if c == '"':
                    break
                buf.append(c)
                j += 1
            raw = "".join(buf)
            j += 1  # 跳过右引号
            s = unescape(raw)
            if s:
                out.append('net.xuele.xuelets.mod.Obf.de("' + encrypt(s) + '")')
                replaced += 1
            else:
                out.append('""')
            i = j
            continue
        out.append(text[i])
        i += 1
    result = "".join(out)
    os.makedirs(os.path.dirname(dst_path), exist_ok=True)
    with open(dst_path, "w", encoding="utf-8") as f:
        f.write(result)
    return replaced


def main():
    if os.path.isdir(DST_DIR):
        shutil.rmtree(DST_DIR)
    total = 0
    for name in FILES:
        src = os.path.join(SRC_DIR, name)
        dst = os.path.join(DST_DIR, name)
        cnt = process(src, dst)
        print("%s: %d strings encrypted" % (name, cnt))
        total += cnt
    # Obf.java 不参与加密，原样复制（解密函数本体保持明文）
    shutil.copyfile(os.path.join(SRC_DIR, "Obf.java"), os.path.join(DST_DIR, "Obf.java"))
    print("Obf.java copied (not encrypted)")
    print("TOTAL", total)


if __name__ == "__main__":
    main()
