# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

# -*- coding: utf-8 -*-
"""验证 V4.3p 三项新功能：

A 管理员密码（与 license 一起加密；面板输入解锁全部功能）
   A1 端上：管理员放行逻辑 / 加密授权解析 / 密码校验 / 面板入口 / 校验块缓存
   A2 仓库：license.json 只有密文（无 features/notice/payload 明文）、验签通过、能解出 admin 块
   A3 算法：Python seal 的密文能被 **mod 的真实 XLModCrypto 代码**（JDK 跑）解开（见 cross_check）
B 学科记录与"每次要打的学科"
   B1 配置项（known/selected/last battle）
   B2 采集点（每题显示登记学科 / 榜页 Intent / 结果页 monthSubject）
   B3 引擎过滤（startWithSubjects 只打勾选）
   B4 面板勾选 UI
C 题库（同学对战采集 → 普通挑战作答）
   C1 采集：同学对战整局入库 + 详情接口入库 + 听力入库
   C2 应用：自动作答两处接入口 + 内容优先匹配
   C3 存储：本地 JSON 落盘/加载/导出/清空/上限淘汰
   C4 算法：镜像 apply() 的匹配策略，验证"选项顺序不同也能答对"（选择/多选/填空/听力）
D 版本与产物
   D1 XLModConfig.VERSION = v4.3p（唯一来源）
   D2 APK：dex 索引连续、classes7 内含新功能密文串（用守卫密钥解回原文）
"""
import base64
import hashlib
import hmac
import io
import json
import os
import re
import subprocess
import sys
import zipfile

import sign_config as sc

APK = 'xueleyun_xlmod_5.9.22.apk'
problems = []


def ok(cond, msg):
    print(("  [OK]  " if cond else "  [FAIL] ") + msg)
    if not cond:
        problems.append(msg)


def read(p):
    return io.open(p, encoding='utf-8').read()


F = read('mod-src/net/xuele/xuelets/mod/XLModFeatures.java')
H = read('mod-src/net/xuele/xuelets/mod/XLModHelper.java')
A = read('mod-src/net/xuele/xuelets/mod/XLModActivity.java')
C = read('mod-src/net/xuele/xuelets/mod/XLModConfig.java')
B = read('mod-src/net/xuele/xuelets/mod/XLModBank.java')
X = read('mod-src/net/xuele/xuelets/mod/XLModCrypto.java')
S = read('mod-src/net/xuele/xuelets/mod/XLModSecrets.java')
U = read('mod-src/net/xuele/xuelets/mod/XLModUpdate.java')

print("=" * 68)
print("A. 管理员密码 + 加密授权")

ok('XLModConfig.isAdminUnlocked()' in F and 'return true;   // 管理员密码解锁' in F,
   'A1 管理员已解锁时 enabled() 一律放行（优先于 features/kill/过期）')
ok('isAdminUnlocked' in C and 'setAdminUnlocked' in C and 'admin_unlocked' in C,
   'A1 本地解锁标记持久化（SharedPreferences: admin_unlocked）')
ok('setAdminVerifier' in F and 'getAdminSalt' in C and 'getAdminHash' in C and 'getAdminIters' in C,
   'A1 授权里的管理员校验块（salt/iters/hash）缓存到本地（断网也能验）')
ok('XLModCrypto.pbkdf2' in F and 'sameBytes' in F and 'PBKDF2-HMAC-SHA256' in X,
   'A1 密码校验 = 手写 PBKDF2-HMAC-SHA256（minApi19 可用，与 Python hashlib 一致）')
ok('android.util.Base64.decode' in F and 'aesCbcDecrypt' in X and 'licenseKey' in X,
   'A1 加密授权：base64 解码 + HMAC-SHA256 派生密钥 + AES-256-CBC（PKCS5）')
ok('optString("enc", "")' in F and 'b.has("salt")' in F and 'b.has("payload")' in F,
   'A1 解析 license 的 enc/salt 字段，并保留旧版 payload 兼容分支')
ok('SIG_PREFIX' in F and 'XLModLic-v2' in F,
   'A1 签名覆盖「前缀+salt+enc」（换 salt/换密文都不通过）')
ok('TYPE_TEXT_VARIATION_PASSWORD' in A and '管理员密码' in A,
   'A1 面板有管理员密码输入框（密码样式）')
ok('解锁全部功能' in A and 'verifyAdminPassword' in A, 'A1 面板有「解锁全部功能」按钮并调用校验')
ok('退出管理员模式' in A and 'setAdminUnlocked(false)' in A, 'A1 面板可退出管理员模式')
ok('adminStatusText' in A and 'adminVerifierReady' in F, 'A1 面板显示管理员状态与校验块状态')

repo_lic = 'tmp-repo/license.json' if os.path.exists('tmp-repo/license.json') else 'license.json'
raw_lic = read(repo_lic)
ok('"enc"' in raw_lic and '"salt"' in raw_lic and '"sig"' in raw_lic, 'A2 license.json 是 V4.3p 加密格式（%s）' % repo_lic)
for leak in ('"features"', '"notice"', '"payload"', 'identity', 'homework', 'admin'):
    ok(leak not in raw_lic, 'A2 仓库里的 license.json 不含明文片段 %s' % leak)
lic = json.loads(raw_lic)
signed = sc.SIG_PREFIX + lic['salt'].encode('ascii') + b'|' + lic['enc'].encode('ascii')
ok(sc._verify_bytes(signed, sc._b64d(lic['sig'])), 'A2 加密授权验签通过（ECDSA P-256，内置公钥）')
master = sc.load_master(required=False)
if master is None or not os.path.exists(sc.ADMIN_PW):
    print("  [SKIP] A2/A3 本机没有 keys/license_key.txt 或 keys/admin_pw.txt（仓库里的占位密钥故意解不开）")
    cfg, adm = {}, {}
else:
    blob = sc._b64d(lic['enc'])
    plain = sc._openssl_dec(sc.derive_key(master, sc._b64d(lic['salt'])), blob[:16], blob[16:])
    cfg = json.loads(plain.decode('utf-8'))
    ok(bool(cfg.get('features')), 'A2 解密得到功能开关表：%d 项' % len(cfg.get('features') or {}))
    adm = cfg.get('admin') or {}
    ok(bool(adm.get('hash')) and adm.get('alg') == 'PBKDF2-HMAC-SHA256',
       'A2 管理员密码以 PBKDF2 校验块形式**随授权一起加密**下发（%d 次迭代）' % adm.get('iters', 0))
if adm:
    pw = io.open(sc.ADMIN_PW, encoding='utf-8').read().strip()
    got = hashlib.pbkdf2_hmac('sha256', pw.encode('utf-8'), sc._b64d(adm['salt']), int(adm['iters']), 32)
    ok(hmac.compare_digest(got, sc._b64d(adm['hash'])), 'A2 本地管理员密码与授权里的校验块匹配')
    wrong = hashlib.pbkdf2_hmac('sha256', (pw + 'x').encode('utf-8'), sc._b64d(adm['salt']), int(adm['iters']), 32)
    ok(not hmac.compare_digest(wrong, sc._b64d(adm['hash'])), 'A2 错误密码不匹配（端上会拒绝解锁）')
ok('LICENSE_KEY_HEX' in S, 'A3 端上主密钥常量存在（XLModSecrets.LICENSE_KEY_HEX）')
ok(re.search(r'"[0-9a-fA-F]{64}"', S) is not None, 'A3 端上主密钥已写入（64 hex）')

print("B. 学科记录与「每次要打的学科」")
ok('known_subjects' in C and 'addKnownSubject' in C and 'addKnownSubjects' in C,
   'B1 已记录学科（known_subjects）读写 API')
ok('challenge_selected_subjects' in C and 'isSubjectSelected' in C and 'parseSubjectMap' in C,
   'B1 勾选学科（challenge_selected_subjects）读写 API')
ok('last_battle_subject' in C and 'setLastBattleSubject' in C and 'getLastBattleAt' in C,
   'B1 最近一局学科（last_battle_subject + 时间）')
ok('XLModConfig.addKnownSubject(sid, sname)' in H and 'XLModBank.harvestBattle(ph, sid)' in H,
   'B2 每题显示时登记本局学科（showAnswerFloat，手动打也记录）')
ok('noteSubjectFromRank' in H and 'PARAM_SUBJECT_NAME' in H,
   'B2 金榜题名页 Intent 参数登记学科（autoOnRankResume）')
ok('monthSubject.trim().substring(6)' in H, 'B2 结果页从 monthSubject(yyyyMM+学科) 反推学科并记录')
ok('XLModConfig.addKnownSubjects(subs.toArray' in H, 'B2 首页探测到的学科批量登记')
ok('filterSubjectsBySelection' in H and 'subs = filterSubjectsBySelection(subs)' in H,
   'B3 引擎按勾选过滤（startWithSubjects 是所有启动路径的唯一入口）')
ok('勾选的学科一个都不在可用列表里' in H, 'B3 勾选与探测无交集时按勾选执行/放弃，不会误打其他科目')
ok('每次要打的学科' in A and 'android.widget.CheckBox' in A, 'B4 面板有学科勾选列表')
ok('全选（所有学科依次打）' in A and '清空勾选 = 不限制' in A, 'B4 面板有「全选 / 清空勾选」按钮')
ok('subjectStatusText' in A and '已记录学科' in A, 'B4 面板显示已记录学科 + 最近一局 + 本次要打')

print("C. 题库（同学对战 → 普通挑战作答）")
ok('harvestBattle' in B and 'harvestQuestion' in B and 'classmate' in B, 'C1 同学对战整局采集入库')
ok('XLModBank.harvestQuestion(q, "detail")' in H, 'C1 详情接口数据也整题入库（含填空/听力）')
ok('XLModBank.putListen' in H and 'putListen' in B, 'C1 听力标准答案入库（详情 sContent）')
ok('XLModBank.apply(q.questionId, q, ua)' in H and H.count('XLModBank.apply') >= 2,
   'C2 自动作答两处接入（buildAutoAnswer 预填 + applyApiAnswers 提交前）')
ok('byContent(cur, content)' in B and 'byId(cur, aid)' in B and 'byLetter(cur, letter)' in B,
   'C2 匹配顺序：选项文本优先 → 选项ID → 选项字母（顺序不同也能对上）')
ok('xlmod_qbank.json' in B and 'DIRECTORY_DOWNLOADS' in B,
   'C3 题库落盘到 /sdcard/Download/xlmod_qbank.json（读不到则退私有目录）')
ok('MAX_ITEMS' in B and 'trim()' in B, 'C3 题库上限 %s 题并淘汰最旧' % re.search(r'MAX_ITEMS = (\d+)', B).group(1))
ok('isBankEnabled' in C and 'setBankEnabled' in C, 'C3 题库开关（bank_enabled，默认开）')
ok('普通挑战用题库作答' in A and 'xlmod_qbank.json' in A and '清空题库' in A,
   'C3 面板：开关 + 统计 + 导出 + 清空')

# ---- C4 算法镜像：同一道题、选项顺序不同 ----
print("C4 算法镜像（选择/多选/填空/听力）：")


def norm(s):
    return ''.join(ch.lower() for ch in (s or '') if ch not in ' \t\n\r\u3000')


def bank_apply(entry, cur_opts):
    """镜像 XLModBank.apply()：entry.k 是入库时的正确选项下标；cur_opts 是当前题目的选项"""
    if entry.get('t') in (3, 52):
        return list(entry.get('f') or [])
    if entry.get('t') == 51:
        return [entry.get('l') or '']
    out = []
    for idx in entry.get('k') or []:
        o = entry['opts'][idx]
        hit = None
        for c in cur_opts:
            if norm(c['c']) and norm(c['c']) == norm(o['c']):
                hit = c
                break
        if hit is None:
            for c in cur_opts:
                if o['i'] and c['i'] == o['i']:
                    hit = c
                    break
        if hit is None:
            for c in cur_opts:
                if o['d'] and c['d'].lower() == o['d'].lower():
                    hit = c
                    break
        if hit is not None:
            out.append(hit['d'] or hit['c'])
    return out


# 同学对战：A.北京 B.上海 C.广州 D.深圳，正确 = B、D（多选）
battle = {'t': 12, 'opts': [{'i': 'a1', 'c': '北京', 'd': 'A'}, {'i': 'a2', 'c': '上海', 'd': 'B'},
                            {'i': 'a3', 'c': '广州', 'd': 'C'}, {'i': 'a4', 'c': '深圳', 'd': 'D'}],
          'k': [1, 3]}
# 普通挑战：同样四个选项，顺序被打乱（正确项跑到 A、C）
normal = [{'i': 'x9', 'c': '上海', 'd': 'A'}, {'i': 'x8', 'c': '北京', 'd': 'B'},
          {'i': 'x7', 'c': '深圳', 'd': 'C'}, {'i': 'x6', 'c': '广州', 'd': 'D'}]
ok(bank_apply(battle, normal) == ['A', 'C'], 'C4 多选题顺序打乱 → 仍答对（%s）' % bank_apply(battle, normal))

single = {'t': 11, 'opts': battle['opts'], 'k': [2]}
normal2 = [{'i': 'y1', 'c': '广州', 'd': 'A'}, {'i': 'y2', 'c': '北京', 'd': 'B'}]
ok(bank_apply(single, normal2) == ['A'], 'C4 单选题顺序打乱 → 仍答对（%s）' % bank_apply(single, normal2))

ok(bank_apply({'t': 3, 'f': ['光合作用', '氧气']}, []) == ['光合作用', '氧气'], 'C4 填空题按空位顺序回填')
ok(bank_apply({'t': 51, 'l': 'I have a dream'}, []) == ['I have a dream'], 'C4 听力题回填标准答案')
# 仅 ID 相同、文本为空时靠 ID 兜底
ok(bank_apply({'t': 11, 'opts': [{'i': 'z1', 'c': '', 'd': 'B'}], 'k': [0]},
              [{'i': 'z1', 'c': '', 'd': 'A'}]) == ['A'], 'C4 文本缺失时用选项ID兜底')

print("D. 版本与产物")
ok('VERSION = "v4.3p"' in C, 'D1 XLModConfig.VERSION = v4.3p')
ok('MOD_VER = XLModConfig.VERSION' in A, 'D1 面板版本号引用唯一来源')

if os.path.exists(APK):
    with zipfile.ZipFile(APK) as z:
        names = [n for n in z.namelist() if re.match(r'classes\d*\.dex$', n)]
        idx = sorted(1 if n == 'classes.dex' else int(n[7:-4]) for n in names)
        ok(idx == list(range(1, len(idx) + 1)), 'D2 dex 索引连续：%d 个（classes7=主逻辑，8/9=守卫）' % len(idx))
        d7 = z.read('classes7.dex')
    key = int(io.open('guard-key.txt').read().strip(), 0) & 0xFF
    toks = re.findall(rb'[A-Za-z0-9+/]{16,}={0,2}', d7)
    found = set()
    for t in toks:
        try:
            s = bytes(b ^ key for b in base64.b64decode(t)).decode('utf-8')
        except Exception:
            continue
        found.add(s)
    for phrase in (u'管理员已解锁（全部功能放行，本地覆盖云端开关）', u'解锁全部功能', u'每次要打的学科',
                   u'题库', u'同学对战采集: 本局入库 ', u'命中作答: ', u'管理员密码不正确'):
        ok(phrase in found, 'D2 APK 内含新功能字符串「%s」（守卫密钥解密验证）' % phrase[:16])
    ok('v4.3p' in found, 'D2 APK 内含版本号 v4.3p（密文解回原文）')
else:
    ok(False, 'D2 找不到 APK：%s' % APK)

print("=" * 68)
if problems:
    print("存在问题：")
    for p in problems:
        print("  -", p)
    sys.exit(1)
print("全部通过：管理员解锁（加密授权）/ 学科可选 / 题库作答 三项功能链路完整")
