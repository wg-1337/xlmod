# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

import zipfile
import shutil

# 仅替换 classes7.dex，其余条目保持原压缩方式复制（apktool 产物无签名，可安全重写）
src = "xueleyun_mod_unsigned_ui.apk"
dst = "xueleyun_mod_injected_ui.apk"
dex = "obf-dex/classes.dex"   # B 级混淆产物（R8 全量重命名 + 字符串加密）

with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for item in zin.infolist():
        if item.filename == "classes7.dex":
            raise SystemExit("ERROR: classes7.dex already present in base")
        # writestr 会保留 item.compress_type（STORED 条目仍是 STORED）
        zout.writestr(item, zin.read(item.filename))
    zi = zipfile.ZipInfo("classes7.dex")
    zi.compress_type = zipfile.ZIP_STORED
    with open(dex, "rb") as f:
        data = f.read()
    zout.writestr(zi, data)

with zipfile.ZipFile(dst) as z:
    infos = z.infolist()
    print("REPLACE OK. entries:", len(infos))
    print("classes7.dex:", z.getinfo("classes7.dex").compress_type, len(data), "bytes")
    stored = [i.filename for i in infos if i.compress_type == 0]
    print("stored entries (should include resources.arsc/libs if originally stored):",
          [n for n in stored if n == "resources.arsc" or n.startswith("lib/")][:6])
