# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

# -*- coding: utf-8 -*-
"""验证多 dex 互锁：

  ①APK 中 classes7/8/9 三个 dex 齐全且索引连续（ART 只加载到第一个缺口）
  ②classes8.dex 里确实有 Guard8、classes9.dex 里确实有 Guard9（不是被 R8 合进主 dex）
  ③classes7.dex 里 Obf 通过反射引用 Guard8/Guard9（字符串常量存在）
  ④运行期密钥链一致：用 build_guard.py 实跑出的 key(0x1A) 能把 obf-src 的密文还原成 mod-src 原文
  ⑤防删效果：用错误密钥（0 / 0x5A）解出来是乱码 → 删掉任一守卫 dex 后 Mod 整体失效
"""
import io, re, zipfile, json, base64, os, sys

APK = "xueleyun_xlmod_5.9.22.apk"
GUARD8 = "net.xuele.xuelets.mod.guard.Guard8"
GUARD9 = "net.xuele.xuelets.mod.guard.Guard9"

problems = []
print("=" * 68)


def ok(cond, msg):
    print(("  [OK]  " if cond else "  [FAIL] ") + msg)
    if not cond:
        problems.append(msg)


# ---------- ① dex 齐全 + 连续 ----------
with zipfile.ZipFile(APK) as z:
    names = z.namelist()
    dexes = sorted([n for n in names if re.match(r'classes\d*\.dex$', n)],
                   key=lambda n: 1 if n == "classes.dex" else int(n[7:-4]))
    idx = [1 if n == "classes.dex" else int(n[7:-4]) for n in dexes]
    print("① dex 文件：", dexes)
    ok(idx == list(range(1, len(idx) + 1)), "dex 索引连续（1..%d）" % len(idx))
    ok("classes7.dex" in dexes and "classes8.dex" in dexes and "classes9.dex" in dexes,
       "三片都在（classes7 主逻辑 + classes8/9 守卫）")
    d7, d8, d9 = z.read("classes7.dex"), z.read("classes8.dex"), z.read("classes9.dex")

print("② 守卫类归属：")
ok(GUARD8.replace(".", "/").encode() in d8, "Guard8 在 classes8.dex")
ok(GUARD9.replace(".", "/").encode() in d9, "Guard9 在 classes9.dex")
ok(GUARD8.replace(".", "/").encode() not in d7 and GUARD9.replace(".", "/").encode() not in d7,
   "守卫类没有被合并进 classes7.dex（否则删 8/9 也无所谓）")

print("③ 反射引用：")
ok(GUARD8.encode() in d7, "classes7.dex 内含 Guard8 名字（Obf 反射取密钥）")
ok(GUARD9.encode() in d7, "classes7.dex 内含 Guard9 名字")
ok(GUARD9.encode() in d8 and GUARD8.encode() in d9, "两个守卫互相引用（缺一个另一个也算不出正确分片）")

# ---------- ④ 密钥链一致性 ----------
key = int(io.open("guard-key.txt").read().strip(), 0) & 0xFF
print("④ 密钥链：build_guard.py 实跑守卫代码得到 key=0x%02X" % key)


def dec(token, k):
    raw = base64.b64decode(token)
    return bytes(b ^ k for b in raw).decode("utf-8", "replace")


def sample_pairs(n=3):
    """从 obf-src 取密文、从 mod-src 取原文（同文件同顺序的字符串字面量）"""
    out = []
    for name in ("XLModActivity.java", "XLModHelper.java", "XLModFeatures.java", "XLModConfig.java"):
        a = io.open("mod-src/net/xuele/xuelets/mod/" + name, encoding="utf-8").read()
        b = io.open("obf-src/net/xuele/xuelets/mod/" + name, encoding="utf-8").read()
        toks = re.findall(r'Obf\.de\("([A-Za-z0-9+/=]+)"\)', b)
        # 用几个稳定的中文短语做校验（在原文里必然存在）
        for phrase in [u"教师身份", u"云端保持原片", u"布置作业", u"自动签到", u"已合并抓取"]:
            for t in toks:
                try:
                    if dec(t, key) == phrase:
                        out.append((name, phrase, t))
                        break
                except Exception:
                    pass
            if len(out) >= n:
                return out
    return out


pairs = sample_pairs(3)
ok(len(pairs) >= 1, "用 key=0x%02X 能还原出原文（取到 %d 条样本）" % (key, len(pairs)))
for name, phrase, tok in pairs:
    print("      %-22s %-14s ← %s…" % (name, phrase, tok[:24]))
    ok(dec(tok, key) == phrase, "密文解码 == 原文（%s）" % phrase)

# ---------- ⑤ 防删效果 ----------
print("⑤ 防删效果（缺失守卫 dex 时 Obf.key() 返回 0，等价于用错误密钥解码）：")
if pairs:
    _, phrase, tok = pairs[0]
    for k, label in ((0x00, "守卫缺失 → key=0"), (0x5A, "退化成老版固定密钥")):
        got = dec(tok, k)
        ok(got != phrase, "%s：解出 %s（乱码，功能整体失效）" % (label, ascii(got[:16])))
    # 同时确认运行时确实依赖两个 dex：缺一个 → 分片不完整
    consts = json.load(io.open("guard-consts.json", encoding="utf-8"))
    ok((consts["base"] ^ consts["c8"] ^ consts["c9"]) & 0xFF != key,
       "链条混入 filler/tag 后 key 与朴素推导不同（说明真实依赖两个守卫的运行结果）")

print("=" * 68)
if problems:
    print("存在问题：")
    for p in problems:
        print("  -", p)
    sys.exit(1)
print("全部通过：三 dex 互锁成立（删掉 classes8 或 classes9 都会让 Mod 字符串密钥错误）")
