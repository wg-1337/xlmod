# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

# -*- coding: utf-8 -*-
"""把三个 dex 一起写回 APK（多 dex 互锁）：

  classes7.dex  ← obf-dex/classes.dex        （Mod 主逻辑，R8 + 字符串加密）
  classes8.dex  ← guard-dex/classes8.dex     （守卫 A：Guard8）
  classes9.dex  ← guard-dex/classes9.dex     （守卫 B：Guard9）

三者缺一不可：Obf 的字符串密钥 = 0x5A ^ Guard8.a() ^ Guard9.b()，
删掉 classes8 或 classes9 会让所有加密字符串变成乱码。
"""
import zipfile
import os

SRC = "xueleyun_mod_unsigned_ui.apk"
DST = "xueleyun_mod_injected_ui.apk"
MAIN = "obf-dex/classes.dex"
GUARDS = [("classes8.dex", "guard-dex/classes8.dex"), ("classes9.dex", "guard-dex/classes9.dex")]

for name, path in [("classes7.dex", MAIN)] + GUARDS:
    if not os.path.exists(path):
        raise SystemExit("ERROR: 缺少 %s（先跑 build_guard.py / R8）" % path)

payload = {"classes7.dex": open(MAIN, "rb").read()}
for name, path in GUARDS:
    payload[name] = open(path, "rb").read()

with zipfile.ZipFile(SRC) as zin, zipfile.ZipFile(DST, "w") as zout:
    for item in zin.infolist():
        if item.filename in payload:
            raise SystemExit("ERROR: %s already present in base" % item.filename)
        zout.writestr(item, zin.read(item.filename))   # 保留原压缩方式
    for name in ["classes7.dex", "classes8.dex", "classes9.dex"]:
        zi = zipfile.ZipInfo(name)
        zi.compress_type = zipfile.ZIP_STORED
        zout.writestr(zi, payload[name])

with zipfile.ZipFile(DST) as z:
    dexes = sorted(n for n in z.namelist() if n.endswith(".dex"))
    print("INJECT OK. dex 列表:", dexes)
    for name in ["classes7.dex", "classes8.dex", "classes9.dex"]:
        info = z.getinfo(name)
        print("  %-14s stored=%s %d bytes" % (name, info.compress_type == zipfile.ZIP_STORED, info.file_size))
    # 连续性检查：classes.dex, classes2.dex ... classesN.dex 必须连续（ART 只加载到第一个缺口）
    idx = []
    for n in dexes:
        if n == "classes.dex":
            idx.append(1)
        else:
            idx.append(int(n[len("classes"):-len(".dex")]))
    idx.sort()
    expect = list(range(1, len(idx) + 1))
    print("索引连续性:", "OK" if idx == expect else "断裂！%s" % idx)
