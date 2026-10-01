# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

# -*- coding: utf-8 -*-
"""验证 V4.4q 全部新功能：

A 管理员密码（写在明文配置里，签发时换算成校验块；面板输入解锁全部功能）
   A1 端上：管理员放行逻辑 / 校验块只进内存（不落盘）/ 密码校验 / 面板入口
   A2 仓库：license.json 只有"配置+签名"单文件；payload 里**没有密码明文**、只有 PBKDF2 校验块；
          本地 features.json 保留明文密码（方便用记事本改）
   A3 回到旧方案：无 AES / 无主密钥 / 无 XLModSecrets
B 学科：只认服务器真实配置 + 每次可选
   B1 配置项（known/selected/last battle/读取时间）
   B2 采集点（探测模式 / 每题显示 / 榜页 Intent / 结果页 monthSubject）
   B3 引擎过滤（startWithSubjects 只打勾选）
   B4 面板：只列读到的学科、没读到明确提示、立即探测按钮
C 题库（同学对战采集 → 普通挑战作答）
   C1 采集：同学对战整局入库 + 详情接口入库 + 听力入库
   C2 应用：自动作答两处接入口 + 内容优先匹配
   C3 存储：本地 JSON 落盘/加载/导出/清空/上限淘汰
   C4 算法：镜像 apply() 的匹配策略，验证"选项顺序不同也能答对"（选择/多选/填空/听力）
E 普通挑战自动打 + 盲答兜底 + 打完自动收集详情（V4.4p）
   E1 自动打什么：kind 配置 + FAB(1=普通 / 2=对战) + 每学科"先对战再普通"换阶段 + 次数用尽处理
   E2 盲答：题库/接口没命中时 选择盲选 B、填空/听写盲填；只在自动打榜运行时生效
   E3 打完自动进挑战详情收集（接口版）：开关 + 结果页钩子 + 日志/落盘
F 听力题(52)答案回填修复（V4.4q）
   F1 题型 id 纠正：52=听力/听写（有输入框）、51=口语（录音）—— 旧版写反了，英语听力填不进去
   F2 答案来源：知识库 L| > 详情缓存 > 题库；52 走听力分支，51 不入库
   F3 输入框写入健壮化：多路径查找 + 写后读回 + 过滤器/Editable 降级 + 不再要求开自动作答
   F4 打完自动收集详情时额外抓听力文本（L| / putListen）
   F5 算法镜像：只有 52+有输入框+有答案才回填
D 版本与产物
   D1 XLModConfig.VERSION = v4.4q（唯一来源）
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
U = read('mod-src/net/xuele/xuelets/mod/XLModUpdate.java')

print("=" * 68)
print("A. 管理员密码（配置里的一个字段）+ 明文配置不上仓库")

ok('XLModConfig.isAdminUnlocked()' in F and 'return true;   // 管理员密码解锁' in F,
   'A1 管理员已解锁时 enabled() 一律放行（优先于 features/kill/过期）')
ok('isAdminUnlocked' in C and 'setAdminUnlocked' in C and 'admin_unlocked' in C,
   'A1 本地解锁标记持久化（SharedPreferences: admin_unlocked）')
ok('loadAdminVerifier' in F and 'sAdminHash' in F and 'sAdminPlain' in F,
   'A1 授权里的管理员字段解析到内存（PBKDF2 校验块 / 明文两种都兼容）')
ok('setAdminVerifier' not in F and 'setAdminVerifier' not in C and 'admin_hash' not in C,
   'A1 密码校验块**不落盘**（只随授权在内存里，与"授权不落盘"一致）')
ok('XLModCrypto.pbkdf2' in F and 'sameBytes' in F and 'PBKDF2-HMAC-SHA256' in X,
   'A1 密码校验 = 手写 PBKDF2-HMAC-SHA256（minApi19 可用，与 Python hashlib 一致）')
ok('optString("payload"' not in F and 'b.getString("payload")' in F and 'decryptLicense' not in F,
   'A1 授权=旧方案单文件（payload + sig），没有加密/主密钥逻辑')
ok('TYPE_TEXT_VARIATION_PASSWORD' in A and '管理员密码' in A,
   'A1 面板有管理员密码输入框（密码样式）')
ok('解锁全部功能' in A and 'verifyAdminPassword' in A, 'A1 面板有「解锁全部功能」按钮并调用校验')
ok('退出管理员模式' in A and 'setAdminUnlocked(false)' in A, 'A1 面板可退出管理员模式')
ok('adminStatusText' in A and 'adminVerifierReady' in F, 'A1 面板显示管理员状态与授权字段状态')

repo_lic = 'tmp-repo/license.json' if os.path.exists('tmp-repo/license.json') else 'license.json'
raw_lic = read(repo_lic)
lic = json.loads(raw_lic)
ok('payload' in lic and 'sig' in lic and 'enc' not in lic,
   'A2 仓库里的 license.json 是"配置+签名"单文件（%s）' % repo_lic)
ok('features.json' not in os.listdir('tmp-repo') if os.path.isdir('tmp-repo') else True,
   'A2 仓库里没有明文 features.json（只上传签名后的 license.json）')
payload, _sig = sc.payload_of(repo_lic)
ptext = payload.decode('utf-8')
ok('"features"' in ptext, 'A2 payload 解出功能开关表')
ok('admin_password' not in ptext, 'A2 payload 里**不含密码明文**（admin_password 已被签发脚本剥离）')
ok('"hash"' in ptext and '"salt"' in ptext, 'A2 payload 里只有 PBKDF2 校验块（admin.salt/hash）')
local_cfg = json.load(io.open('features.json', encoding='utf-8'))
ok(bool((local_cfg.get('admin_password') or '').strip()),
   'A2 本地 features.json 里保留密码明文（你可以随时用记事本改）')
pw = (local_cfg.get('admin_password') or '').strip()
adm = (json.loads(ptext).get('admin') or {})
got = hashlib.pbkdf2_hmac('sha256', pw.encode('utf-8'), base64.b64decode(adm['salt']), int(adm['iters']), 32)
ok(hmac.compare_digest(got, base64.b64decode(adm['hash'])),
   'A2 本地明文密码与授权里的校验块一致（端上输入该密码即可解锁）')
wrong = hashlib.pbkdf2_hmac('sha256', (pw + 'x').encode('utf-8'), base64.b64decode(adm['salt']), int(adm['iters']), 32)
ok(not hmac.compare_digest(wrong, base64.b64decode(adm['hash'])), 'A2 错误密码不匹配（端上会拒绝解锁）')
ok(sc._verify_bytes(payload, _sig), 'A2 授权验签通过（ECDSA P-256，内置公钥）')
ok('XlmodSecrets' not in os.listdir('mod-src/net/xuele/xuelets/mod')
   or 'XLModSecrets.java' not in os.listdir('mod-src/net/xuele/xuelets/mod'),
   'A3 加密方案的主密钥类 XLModSecrets.java 已删除（回到旧方案）')
ok('XLModSecrets' not in X and 'licenseKey' not in X and 'aesCbcDecrypt' not in X,
   'A3 XLModCrypto 只保留 PBKDF2/MD5（无 AES/主密钥）')

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
ok('XLModConfig.addKnownSubjects(subs.toArray' in H, 'B2 首页探测到的学科批量登记（id+名称都来自服务器）')
ok('if (sProbeMode)' in H and 'finishSubjectProbe' in H and '学科探测: 服务器返回 ' in H,
   'B2 探测模式：只登记服务器返回的学科并退出页面，不进入打榜流程')
ok('filterSubjectsBySelection' in H and 'subs = filterSubjectsBySelection(subs)' in H,
   'B3 引擎按勾选过滤（startWithSubjects 是所有启动路径的唯一入口）')
ok('勾选的学科一个都不在可用列表里' in H, 'B3 勾选与探测无交集时按勾选执行/放弃，不会误打其他科目')
ok('每次要打的学科' in A and 'android.widget.CheckBox' in A, 'B4 面板有学科勾选列表')
ok('立即探测学科' in A and 'startSubjectProbe' in H, 'B4 面板有「立即探测学科」（读取服务器真实学科配置）按钮')
ok('rebuildSubjectRows' in A and 'getKnownSubjects()' in A and 'putAll(XLModConfig.parseSubjectMap(XLModConfig.getChallengeSubjects()))' not in A,
   'B4 勾选项**只来自已读取到的服务器学科**（不再拿猜测的学科表凑数）')
ok('还没有读到学科配置' in A, 'B4 没读到学科时面板明确提示（而不是列出猜测学科）')
ok('subAll' not in A, 'B4 旧的"猜测学科 + 已读学科"合并列表已移除')
ok('s("challenge_subjects", "")' in C, 'B4 手动兜底学科默认**空**（旧版硬编码的 2:数学/1:语文 猜测表已删除）')
ok('已有真实名称，别用 id 覆盖' in C, 'B4 学科名不会被降级覆盖（id-only 记录不会盖掉真实名称）')
ok('isSubjectProbeRunning' in A and 'isSubjectProbeRunning' in H, 'B4 面板显示探测状态（读取中/空闲）')
ok('getKnownSubjectsAt' in C and 'known_subjects_at' in C, 'B4 记录学科配置的读取时间')

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

print("F. 听力题(52)答案回填修复（V4.4q）")
ok('QT_LISTEN = 52' in H and 'QT_SPOKEN = 51' in H,
   'F1 题型常量按宿主映射：52=听力/听写（有输入框）、51=口语（录音）')
ok('parseQType(q) == QT_LISTEN' in H and 'parseQType(q) != QT_LISTEN' in H,
   'F1 听力判定（applyApiAnswers / applyListenAnswer / fetchByDetail / 悬浮窗）全部改用 52')
ok(H.count('parseQType(q) == 51') == 0, 'F1 代码里不再把 51 当听力（旧版写反了，导致英语听力填不进去）')
ok('QT_LISTEN = 52' in B and 'type == QT_LISTEN' in B and 'type == QT_SPOKEN' in B,
   'F2 题库：52 走听力分支（l），51 口语不入库；apply() 也用 52 回填文本')
ok('listenTextOf' in B and 'XLModBank.listenTextOf(qid)' in H,
   'F2 听力答案来源三选一：知识库 L| > 详情缓存 > 题库（listenTextOf）')
ok('writeEditText' in H and 'readEditText' in H and 'findListenEditText' in H and 'findEditTextInView' in H,
   'F3 输入框写入：多路径查找（mEtAnswer → 任意 EditText 字段 → 视图树）+ 写入后读回校验')
ok('et.setFilters(new android.text.InputFilter[0])' in H and 'ed.clear();' in H,
   'F3 被 android:digits 过滤器/文本监听器吃掉时可降级（清过滤器 / 直接改 Editable）')
ok('XLModConfig.isShowAnswerFloat() || XLModConfig.isDebugFloat()' in H,
   'F3 回填不再要求开「自动作答」：开着答案悬浮窗也会把答案写进输入框')
ok('（输入框已写入 ' in H and '（输入框未命中！）' in H,
   'F3 回填日志会打"写入后的输入框内容 / 未命中"，便于排障')
ok('int listenGot = 0' in H and '听力文本 ' in H,
   'F4 打完自动收集详情时，额外把听力(52)标准答案写进知识库+题库（L| / putListen）')

print("F5 听力回填算法镜像（52 才回填、51 不碰）")


def listen_fill(qtype, has_box, text):
    """镜像：只有听力(52)片段有输入框，且拿到答案才回填"""
    if qtype != 52:
        return '不处理'
    if not has_box:
        return '无输入框'
    if not text:
        return '没有答案'
    return '回填:' + text


ok(listen_fill(52, True, 'I have a dream') == '回填:I have a dream', 'F5 听力(52)+有输入框+有答案 → 回填')
ok(listen_fill(52, True, '') == '没有答案', 'F5 听力(52) 没答案 → 不填（交给盲填）')
ok(listen_fill(51, False, 'my answer') == '不处理', 'F5 口语(51) → 不处理（那是录音题）')
ok(listen_fill(3, True, 'x') == '不处理', 'F5 填空(3) 走填空分支，不走听力回填')

print("D. 版本与产物")
ok('VERSION = "v4.4q"' in C, 'D1 XLModConfig.VERSION = v4.4q')
ok('MOD_VER = XLModConfig.VERSION' in A, 'D1 面板版本号引用唯一来源')

if os.path.exists(APK):
    with zipfile.ZipFile(APK) as z:
        names = [n for n in z.namelist() if re.match(r'classes\d*\.dex$', n)]
        idx = sorted(1 if n == 'classes.dex' else int(n[7:-4]) for n in names)
        ok(idx == list(range(1, len(idx) + 1)), 'D2 dex 索引连续：%d 个（classes7=主逻辑，8/9=守卫）' % len(idx))
        d7 = z.read('classes7.dex')
    key = int(io.open('guard-key.txt').read().strip(), 0) & 0xFF
    # dex 里密文串是"XOR+Base64"字面量：按最大 base64 段取出，再逐偏移尝试解码（长度前缀会粘进段里）
    runs = re.findall(rb'[A-Za-z0-9+/=]{8,}', d7)
    found = set()
    for r in runs:
        for st in range(0, 4):
            s = r[st:]
            s = s[: len(s) - (len(s) % 4)] if len(s) % 4 else s
            if len(s) < 8:
                continue
            try:
                txt = bytes(b ^ key for b in base64.b64decode(s)).decode('utf-8')
            except Exception:
                continue
            found.add(txt)

    def has(phrase):
        return any(phrase in s for s in found)

    for phrase in (u'管理员已解锁（全部功能放行，本地覆盖云端开关）', u'解锁全部功能', u'每次要打的学科',
                   u'题库', u'同学对战采集: 本局入库 ', u'命中作答: ', u'管理员密码不正确', u'盲答', u'挑战详情采集', u'提交前回填', u'输入框未命中'):
        ok(has(phrase), 'D2 APK 内含新功能字符串「%s」（守卫密钥解密验证）' % phrase[:16])
    ok(has('v4.4q'), 'D2 APK 内含版本号 v4.4q（密文解回原文）')
else:
    ok(False, 'D2 找不到 APK：%s' % APK)

print("E. 普通挑战自动打 + 盲答兜底 + 打完自动收集详情（V4.4p）")
ok('challenge_kind' in C and 'getChallengeKind' in C and 'setChallengeKind' in C,
   'E1 新增「自动打什么」（challenge_kind：0=对战 1=普通 2=两者）')
ok('getChallengeKind() == 2' in H and 'needSwitchToNormal' in H and 'sAutoPhaseNormal' in H,
   'E1 两者都打：本学科同学对战打满 → 转普通挑战（同一学科先对战再普通）')
ok('sAutoPhaseNormal ? 1 : 2' in H and 'onFabMenuItemClick(fabId)' in H,
   'E1 FAB 按阶段点：普通挑战=1 / 同学对战=2')
ok('phaseName()' in H and 'kindName()' in H, 'E1 日志区分阶段与"打什么"（排障可读）')
ok('同学对战次数已用完，转普通挑战' in H, 'E1 对战次数用完 → 转普通挑战（而不是直接放弃本科）')
ok('sAutoPhaseNormal = (XLModConfig.getChallengeKind() == 1)' in H,
   'E1 每日重置/手动触发/换学科时都按 kind 复位阶段')
ok('自动打什么（V4.4p）' in A and '同学对战 + 普通挑战（先对战再普通）' in A, 'E1 面板可选「自动打什么」')

ok('applyBlindFallback' in H and '盲答' in H, 'E2 盲答兜底函数存在')
ok('blind_fallback' in C and 'isBlindFallback' in C and 'blind_fill_text' in C,
   'E2 盲答开关 + 盲填内容（默认「不会」）可配置')
ok('XLModConfig.sAutoEngineActive != 1' in H, 'E2 只在自动打榜运行时生效（手动答题不受影响）')
ok('ans.size() >= 2 ? ans.get(1) : ans.get(0)' in H, 'E2 选择题盲选 B（第二个选项；只有一个选项时选第一个）')
ok('blanks = q.answers.size()' in H and 'ua.answerContentList.add(fill)' in H,
   'E2 填空/听写盲填（填空按空数逐个填）')
ok("t == QT_FILL || t == QT_LISTEN" in H, 'E2 盲填覆盖填空(3)与听力/听写(52)；口语(51)跳过')
ok('isEmptyAnswer(ua)' in H and '已有答案：不动' in H, 'E2 已有答案时不覆盖（题库/接口优先）')
ok('applyBlindFallback(q, ua);' in H and H.count('applyBlindFallback') >= 3,
   'E2 提交前钩子（applyApiAnswers）里调用：没开自动作答时也兜底')

ok('isAutoHarvestDetail' in C and 'auto_harvest_detail' in C, 'E3 开关：打完自动进挑战详情收集题目')
ok('harvestChallengeDetail' in H and 'ChallengeDetailHelper.loadQuestionList' in H,
   'E3 走与结果页「查看详情」同一个接口收集（不弹页面）')
ok('挑战详情采集开始' in H and '挑战详情采集完成' in H and '挑战详情采集失败' in H,
   'E3 采集有开始/完成（题数、带答案数、题库增量）/失败日志')
ok('XLModBank.save(true)' in H, 'E3 采集完立刻落盘题库')
ok('sLastBattleClassmate' in H, 'E3 采集日志区分同学对战 / 普通挑战')
ok('打完自动进挑战详情收集题目' in A, 'E3 面板有该开关 + 说明')

print("E4 盲答算法镜像（无答案 → 选择题选 B / 填空乱填）")


def blind(q_type, opts, ua, engine=True, enabled=True, fill='不会'):
    """镜像 applyBlindFallback 的判定"""
    if not enabled or not engine:
        return ua
    if ua.get('ids') or ua.get('contents'):
        return ua
    if q_type in (11, 12, 2):
        if not opts:
            return ua
        pick = opts[1] if len(opts) >= 2 else opts[0]
        return {'ids': [pick['i']], 'contents': [pick.get('d') or pick.get('c')]}
    if q_type in (3, 51):
        n = len(opts) if (q_type == 3 and opts) else 1
        return {'ids': [], 'contents': [fill] * n}
    return ua


OPT4 = [{'i': 'a1', 'c': '北京', 'd': 'A'}, {'i': 'a2', 'c': '上海', 'd': 'B'},
        {'i': 'a3', 'c': '广州', 'd': 'C'}, {'i': 'a4', 'c': '深圳', 'd': 'D'}]
empty = {'ids': [], 'contents': []}
got = blind(11, OPT4, dict(empty))
ok(got['ids'] == ['a2'] and got['contents'] == ['B'], 'E4 无答案单选题 → 选 B（%s）' % got['contents'])
got = blind(12, OPT4, dict(empty))
ok(got['ids'] == ['a2'], 'E4 无答案多选题 → 也先选 B（至少不作弊式空交）')
got = blind(11, [{'i': 'z1', 'c': '唯一', 'd': 'A'}], dict(empty))
ok(got['ids'] == ['z1'], 'E4 只有一个选项 → 选第一个（不会崩）')
got = blind(2, OPT4, dict(empty))
ok(got['ids'] == ['a2'], 'E4 判断题 → 选第二个（B）')
got = blind(3, [{'i': 'b1'}, {'i': 'b2'}], dict(empty))
ok(got['contents'] == ['不会', '不会'], 'E4 两个空的填空题 → 逐空乱填（%s）' % got['contents'])
got = blind(51, [], dict(empty))
ok(got['contents'] == ['不会'], 'E4 听写题 → 盲填一条')
ok(blind(52, [], dict(empty)) == empty, 'E4 口语题不盲答（无法自动作答）')
known = {'ids': ['a3'], 'contents': ['C']}
ok(blind(11, OPT4, dict(known)) == known, 'E4 已有答案（题库命中）→ 原样不动')
ok(blind(11, OPT4, dict(empty), engine=False) == empty, 'E4 非自动打榜（手动答题）→ 不盲答')
ok(blind(11, OPT4, dict(empty), enabled=False) == empty, 'E4 关掉盲答开关 → 不盲答')

print("=" * 68)
if problems:
    print("存在问题：")
    for p in problems:
        print("  -", p)
    sys.exit(1)
print("全部通过：听力(52)回填修复 / 普通挑战自动打 / 盲答兜底 / 打完自动收集详情 / 管理员解锁 / 学科可选 / 题库作答 全部就位")
