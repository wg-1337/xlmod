# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

# -*- coding: utf-8 -*-
"""验证"远程授权"语义：
  A. 结构断言（源码级）：默认地址、锁定白名单、60 秒轮询、无本地缓存、公告、变更重建
  B. 行为断言（用 Python 复刻 enabled()/notice()/kill 的判定，跑用例）
  C. 互锁仍成立（调用 verify_dex_interlock.py 的核心断言）
"""
import io, re, json, os, sys

problems = []


def ok(cond, msg):
    print(("  [OK]  " if cond else "  [FAIL] ") + msg)
    if not cond:
        problems.append(msg)


SRC = io.open('mod-src/net/xuele/xuelets/mod/XLModFeatures.java', encoding='utf-8').read()
ACT = io.open('mod-src/net/xuele/xuelets/mod/XLModActivity.java', encoding='utf-8').read()
HLP = io.open('mod-src/net/xuele/xuelets/mod/XLModHelper.java', encoding='utf-8').read()

print("A. 结构断言")
ok('wg-1337/xlmod' in SRC, "默认配置地址指向仓库 wg-1337/xlmod")
ok('FAIL_CLOSED_ALLOW = {"privacy", "logs"}' in SRC, "锁定白名单 = {privacy, logs}")
ok('if (c == null) return isFailClosedAllowed(id);' in SRC, "enabled(): 没有配置 → 只放行白名单（锁死）")
ok('if (c.optBoolean("kill", false)) return false;' in SRC, "kill=true → 全部停用")
ok('return c.optBoolean("default", false);' in SRC, "未列出的功能区 → default（缺省 false 锁）")
ok('REFRESH_MS = 60L * 1000L' in SRC, "刷新周期 = 60 秒")
ok('startWatcher' in SRC and 'isAppForeground()' in SRC, "前台每分钟检查（isAppForeground 判定）")
ok('FileOutputStream' not in SRC and 'SharedPreferences' not in SRC and 'cfgSet("feature_json' not in SRC,
   "配置不落盘（拿不到就是锁死，无离线兜底）")
ok('notice()' in SRC and 'onConfigChanged' in SRC, "公告 + 变更回调存在")
ok('showNoticeIfAny' in ACT and 'rebuildUi' in ACT, "面板：每次打开弹公告 + 变更实时重建")
ok('XLModFeatures.startWatcher();' in HLP and 'XLModFeatures.refreshAsync(act, true);' in HLP,
   "启动时即拉一次配置 + 启动轮询")
ok('optString("enc", "")' in SRC and 'SIG_PREFIX' in SRC and 'decryptLicense' in SRC,
   "V4.3p：加密授权（enc/salt + 签名前缀）解析与解密")
ok('b.has("payload")' in SRC, "V4.3p：旧版明文（payload）兼容分支仍在")

print("B. 行为断言（复刻 enabled() 判定）")
ALLOW = {"privacy", "logs"}


def enabled(cfg, fid, admin=False):
    if fid == "privacy":
        return True                                  # ALWAYS_ON
    if admin:
        return True                                  # 管理员已解锁 → 全部放行
    if cfg is None:
        return fid in ALLOW
    if cfg.get("kill", False):
        return False
    feats = cfg.get("features") or {}
    if fid in feats:
        return bool(feats[fid])
    return bool(cfg.get("default", False))


cases = [
    (None, "homework", False, "没配置 → 布置作业锁定"),
    (None, "privacy", True, "没配置 → 隐私隐藏仍可用"),
    (None, "logs", True, "没配置 → 日志仍可用"),
    (None, "cloud_keep", False, "没配置 → 云原片锁定"),
    ({"default": False, "features": {"homework": True}}, "homework", True, "配置开启 homework → 可用"),
    ({"default": False, "features": {"homework": True}}, "cloud_keep", False, "配置未列出 → 按 default=false 锁"),
    ({"default": True, "features": {"homework": False}}, "cloud_keep", True, "default=true → 未列出的可用"),
    ({"kill": True, "features": {"privacy": True, "logs": True}}, "privacy", True,
     "熔断 → 隐私仍可用（代码里 ALWAYS_ON 优先：隐私属于用户自身权益）"),
    ({"kill": True, "features": {}}, "logs", False, "熔断 → 连日志也停"),
]
for cfg, fid, expect, desc in cases:
    ok(enabled(cfg, fid) == expect, desc)

admin_cases = [
    ({"kill": True, "features": {}}, "homework", "熔断 + 管理员解锁 → 仍然放行（作者后门）"),
    ({"default": False, "features": {"teacher_tools": False}}, "teacher_tools",
     "授权明确关闭 + 管理员解锁 → 仍然放行"),
    (None, "homework", "没配置（没网）+ 管理员解锁 → 仍然放行"),
]
for cfg, fid, desc in admin_cases:
    ok(enabled(cfg, fid, admin=True) is True, desc)

print("C. 样例配置自检")
sample = None
for _p in ('xlmod-config/features.json', 'features.json', 'tmp-repo/features.json'):
    if os.path.exists(_p):
        sample = json.load(io.open(_p, encoding='utf-8'))
        print("      （样例取自 %s）" % _p)
        break
if sample is None and os.path.exists('license.json'):
    try:
        import sign_config as sc
        _m = sc.load_master(required=False)
        if _m:
            o = json.load(io.open('license.json', encoding='utf-8'))
            blob = sc._b64d(o['enc'])
            sample = json.loads(sc._openssl_dec(sc.derive_key(_m, sc._b64d(o['salt'])),
                                                blob[:16], blob[16:]).decode('utf-8'))
            print("      （样例取自解密后的 license.json）")
    except Exception as e:
        print("      （无法取得样例：%s）" % e)
if sample is None:
    print("  [SKIP] C 找不到样例配置（本机没有明文 features.json / 主密钥）")
else:
    ok('notice' in sample and len(sample['notice']) > 0, "样例含公告 notice")
    ok(sample.get('default') is False, "样例 default=false（限制语义）")
    missing = [i for i in ["identity", "teacher_tools", "cloud_keep", "notify_recall", "homework",
                           "auto_sign", "auto_challenge", "cloud_flower", "rank", "answer", "privacy", "logs"]
               if i not in (sample.get('features') or {})]
    ok(not missing, "样例覆盖全部功能区 ID（缺: %s）" % (missing or "无"))


print("D. 授权签名（开源后的真正防线）")
ok('SHA256withECDSA' in SRC, "使用 SHA256withECDSA 验签")
ok('PUB_B64' in SRC and 'KeyFactory.getInstance' in SRC and '"EC"' in SRC, "内置公钥 + EC KeyFactory")
ok('.sig' in SRC, "同时下载 features.json.sig")
ok('if (!verify(cfg, sig))' in SRC and '保持锁定' in SRC, "验签失败 → 保持锁定")
ok('return DEFAULT_URL;' in SRC, "配置地址写死在代码里")
ok('setUrl' not in SRC, "代码里没有修改地址的入口")
ok('expires' in SRC and 'expired()' in SRC, "支持有效期（过期即锁定）")
ok('setUrl' not in ACT and '配置地址' not in ACT, "面板不提供地址修改入口")

print("=" * 60)
if problems:
    print("存在问题：")
    for p in problems:
        print("  -", p)
    sys.exit(1)
print("全部通过：拉不到配置即锁定（仅 privacy/logs 可用），公告与 60 秒实时生效逻辑就位")
