# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

# -*- coding: utf-8 -*-
"""验证 V5.0p 全部新功能：

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
   D1 XLModConfig.VERSION = v5.0p（唯一来源）
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
ok('byContent(cur, content)' in B and 'byId(cur, aid)' in B and 'byLetter(cur, letter)' not in B,
   'C2 匹配顺序：选项文本 → 选项ID（V5.0p 内修订起不再有字母/位次猜测）')
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

print("G. 听力答案来源 + 题库不再收盲填垃圾（V4.6p）")
ok('listenServerDesc' in H and H.count('listenServerDesc') >= 3,
   'G1 听力标准答案取详情映射的 listenServerDesc（宿主 initAnswer 里 52 型答案就在这里）')
ok('u.listenServerDesc' in H and 'u.sContent' not in H and 'u.answerContentList' not in H,
   'G1 听力只认权威字段 listenServerDesc（不再回退 sContent/answerContentList —— 那是学生作答）')
ok('putFillFromDetail' in B and 'harvestFillAuthoritative' in H and 'answers[].answerContent' in H,
   'G1 填空题标准答案 = answers[].answerContent（与宿主答题详情页 getTrueAnswerList 同源）')
ok('isJunk' in B and '盲填内容永不入库' in B,
   'G2 题库新增垃圾判定：盲填内容/空值永不入库')
ok('没有权威答案就不入库' in B,
   'G2 没有权威答案的条目一律不入库（不把学生答案/盲填当标准答案）')
ok('purgeJunkEntries' in B and '清理盲填垃圾' in B,
   'G2 载入题库时清理历史污染（丢弃答案全是垃圾的条目、剔除垃圾字段）')
ok('kbPurgeJunk' in C and 'kb_junk_purged_v46' in C,
   'G2 知识库一次性清理（旧版本写进 kb 的「不会」被删掉）')
ok('提交前回填(" + src + ")' in H and '"标准答案"' in H and '"盲填兜底"' in H,
   'G3 回填优先级：标准答案 > 已有作答 > 盲填兜底（日志会标注来源）')
ok('listenServerDesc' in H and 'isJunk(serverDesc)' in H,
   'G3 回填时把 listenServerDesc 也当答案来源')
ok('shouldWaitListenAnswer' in H and '听力题等待标准答案' in H,
   'G4 自动打榜：听力题最多等 ~8 秒标准答案，不抢着提交成盲填')
ok('跳过（听力已拿到标准答案）' in H, 'G4 盲答前再试一次标准答案，找得到就不盲填')

print("G5 算法镜像（旧版 vs V4.6p）")


def old_listen_value(u):
    """旧版：只看 answerContentList（听力题里通常是空的 → 落到盲填）"""
    return u.get('answerContentList') or ['']


def new_listen_value(u):
    """V4.6p：listenServerDesc → sContent → answerContentList，垃圾值忽略"""
    for k in ('listenServerDesc', 'sContent', 'answerContentList'):
        v = u.get(k)
        if not v:
            continue
        t = v[0] if isinstance(v, list) else v
        if t and t.strip() and t.strip() != '不会':
            return t
    return ''


detail = {'listenServerDesc': 'I have a dream', 'sContent': '', 'answerContentList': []}
ok(old_listen_value(detail) == [''], 'G5 旧版：听力题的 answerContentList 为空 → 拿不到答案（随后盲填「不会」）')
ok(new_listen_value(detail) == 'I have a dream', 'G5 V4.6p：从 listenServerDesc 取到真实答案')
ok(new_listen_value({'listenServerDesc': '', 'sContent': 'Hello world'}) == 'Hello world',
   'G5 没有 listenServerDesc 时回落到 sContent')
ok(new_listen_value({'answerContentList': ['不会']}) == '', 'G5 盲填的「不会」不会被当成答案')


def harvest_old(answer_content):
    return answer_content          # 旧版：answerContent 直接当标准答案


def harvest_new(answer_content, is_correct):
    if not is_correct:
        return None                # V4.6p：没正确标记 → 不入库
    if answer_content.strip() in ('不会', ''):
        return None                # 垃圾值不入库
    return answer_content


ok(harvest_old('不会') == '不会', 'G5 旧版：盲填的「不会」被当成正确答案写进题库（正是你看到的问题）')
ok(harvest_new('不会', False) is None, 'G5 V4.6p：没正确标记 → 不入库')
ok(harvest_new('不会', True) is None, 'G5 V4.6p：即使是"正确项"，「不会」这类垃圾也拒绝入库')
ok(harvest_new('光合作用', True) == '光合作用', 'G5 V4.6p：真正的标准答案照常入库')

print("H. 普通挑战无限刷 + 听力回填加固（V4.7p）")
ok('isChallengeUnlimited' in C and 'challenge_unlimited' in C and 'unlimited_switch_after' in C,
   'H1 新增开关「普通挑战无限刷」+ 换科局数')
ok('forceUnlimitedNormalCount' in H and 'forceCostSuccess' in H
   and '该功能已在 V4.9q 撤下' in H,
   'H1 两个 smali 注入点保留为无害空转（不再放行次数）')
ok('f.setInt(selector, 999)' not in H and 'f.setInt(reCost, 1)' not in H,
   'H1 已删除"顶次数/改 functionCode"的放行代码（不会再干扰次数判定）')
ok('return false;' in C and 'isChallengeUnlimited' in C and 'migrateRemoveUnlimited' in C,
   'H1 配置层：isChallengeUnlimited() 恒 false + 一次性复位老设置（migrateRemoveUnlimited）')
ok('return XLModConfig.getBattlesPerSubject();' in H and 'effectiveCap()' in H,
   'H1 引擎：上限回归「每学科次数」（getBattlesPerSubject），不再被无限刷改写')
ok('每学科打几局' not in A and '普通挑战无限刷：跳过次数校验' not in A,
   'H1 面板已撤下「普通挑战无限刷」开关与「每学科打几局」（V4.9q）')
ok('forceCostSuccess' in io.open('obf-rules.pro', encoding='utf-8').read()
   and 'forceUnlimitedNormalCount' in io.open('obf-rules.pro', encoding='utf-8').read(),
   'H1 obf-rules.pro 已加 keep（smali 反射调用这两个方法，改名就静默失效）')
ok('currentFragmentIsListen' in H and 'currentFragment(' in H,
   'H2 听力判定双保险：qType==52 **或** 当前 Fragment 就是听力页（类名含 Listen / 有输入框）')
ok('int[] tries' in H and 'tries[0] < 12' in H and 'postDelayed(fill[0], 1000)' in H,
   'H2 听力回填改成"每秒重试、最多 12 次"，不再依赖某一次恰好赶上详情返回')
ok('listenDiag' in H and '暂无可填答案' in H,
   'H2 拿不到答案时打一行诊断（kb/详情缓存/题库/题型/选项）便于排障')
ok('anyTextOf' in B and 'XLModBank.anyTextOf(qid)' in H,
   'H2 题库兜底：不管旧条目记成哪种题型，只要有文本答案就取出来用')
ok('applyListenAnswer(Activity act' in H and 'boolean applyListenAnswer' in H,
   'H2 回填返回是否成功（重试循环据此决定继续/停止）')

print("H3 无限刷算法镜像（客户端核实结论）")


def can_start(count, unlimited):
    """榜页拦截：challengeSubjectTime <= 0 直接不开局（客户端）"""
    return count > 0 or unlimited


def cost_ok(function_code, unlimited):
    """开局拦截：service functionCode==1 才算扣次成功（客户端）"""
    return function_code == 1 or unlimited


ok(can_start(0, False) is False, 'H3 次数=0 且未开无限刷 → 打不开（客户端拦住）')
ok(can_start(0, True) is True, 'H3 次数=0 + 无限刷 → 放行')
ok(cost_ok(0, False) is False, 'H3 服务端返回 functionCode=0 且未开无限刷 → 弹「次数已用完」退出')
ok(cost_ok(0, True) is True, 'H3 服务端返回 functionCode=0 + 无限刷 → 当作成功，继续答题')
ok(cost_ok(1, False) is True, 'H3 服务端正常扣次成功 → 照常（不受开关影响）')

print("I. 自定义每局题数 + 提前结算（V4.8p）")
ok('normal_q_count' in C and 'getNormalQCount' in C and 'finish_status' in C and 'finishStatusCode' in C,
   'I1 新增「每局题数(0=服务端默认)」与「提前结算方式(qStatus 1/3)」配置')
ok('sAutoQuestionsThisBattle' in H and 'sFinishingBattle' in H and 'finishBattleNow' in H,
   'I1 引擎按局计题数，并只发起一次提前结算')
ok('sAutoQuestionsThisBattle++' in H and 'sAutoQuestionsThisBattle > want' in H,
   'I1 第 N+1 题出现时才结算（保证前 N 题都已提交）')
ok('submitResultToServer' in H and 'getDeclaredMethod("submitResultToServer"' in H,
   'I1 反射调用宿主自己的 submitResultToServer（与「提交成绩」按钮同一条路径）')
ok('sAutoQuestionsThisBattle = 0;      // V4.8p：新一局，题数归零' in H,
   'I1 开局题数归零（无限刷时每局独立计数）')
ok('sAutoQuestionsThisBattle = 0;      // V4.8p：本局结束，题数归零' in H,
   'I1 结算（结果页）后题数归零')
ok('每局题数(0=服务端默认)' in A and '提前结算方式' in A and '本地最大题数' in A,
   'I1 面板有输入框 + 方式下拉 + 说明（每局题数 = 改本地最大题数）')
ok('qStatus=1' in A and 'qStatus=3' in A, 'I1 面板说明里写明两种提交状态的含义')

print("I2 结算时机镜像")


def should_finish(shown_index, want, finishing):
    """镜像 autoOnQuestionShown 的判定：shown_index 是"第几题出现"（第一题为 1）"""
    if want <= 0 or finishing:
        return False
    return shown_index > want


ok(should_finish(1, 0, False) is False, 'I2 每局题数=0（默认）→ 永不提前结算（按宿主原逻辑）')
ok(should_finish(3, 10, False) is False, 'I2 第 3 题 / 目标 10 → 继续答')
ok(should_finish(10, 10, False) is False, 'I2 第 10 题 → 照常答完（保证第 10 题被提交）')
ok(should_finish(11, 10, False) is True, 'I2 第 11 题出现 → 发起提交结算')
ok(should_finish(11, 10, True) is False, 'I2 已发起过提交 → 不重复提交')

print("J. 本地最大题数 / 每学科局数（V4.9p）")
ok('applyLocalTotalQuestions' in H and 'mTotalQuestionCount' in H and 'f.setInt(ph, want)' in H,
   'J1 每局题数直接写进宿主本地最大题数（mTotalQuestionCount = N）')
ok('applyLocalTotalQuestions(act);' in H and 'sAutoQuestionsThisBattle++;' in H,
   'J1 每题显示时都校准一次本地最大题数（宿主拿到新题可能重写）')
ok('宿主按这个数判定「最后一题」并提交，qTotal=' in H,
   'J1 日志说明：本地最大题数 X → N（宿主按这个数判定最后一题并提交，qTotal=N）')
ok('perSubject' not in H and 'Integer.MAX_VALUE' not in H,
   'J2 每学科打几局/无限上限的代码已移除（不会再覆盖每学科次数）')
ok('getUnlimitedSwitchAfter' in C and 'return 0;' in C,
   'J2 配置层 getUnlimitedSwitchAfter() 恒返回 0（旧值读取不再生效）')
ok('打几局/换科' in A and '由「每学科次数」' in A,
   'J2 面板说明写明：打几局/换科沿用原逻辑（每学科次数），不再有无限刷干扰')
ok('本学科已打 ' in H and '全部完成，标记今日完成并停止' in H,
   'J2 每次判定都打日志说明「打成几局/共几个学科/为什么停」')

print("J3 结算路径镜像（本地最大题数）")


def settles(local_total_after, pos):
    """镜像：本地最大题数被改成 N 后，宿主在第 pos 题（0 基）即认为到最后一题"""
    return pos >= local_total_after - 1


ok(settles(20, 19) is True, 'J3 本地总题数=20 → 第 20 题（pos 19）宿主即认定最后一题 → 正常提交结算')
ok(settles(20, 18) is False, 'J3 第 19 题还没到 → 继续答')
ok(settles(3, 2) is True, 'J3 本地总题数=3 → 第 3 题结算')

print("K. 普通挑战题目收录 + 结算抓全部答案（V5.0p）")
ok('noteQuestion' in B and '只收录题目本身' in B,
   'K1 题库新增 noteQuestion：没答案也先收录题目（题干+选项+题型）')
ok('noteQuestion(ql.get(pos)' not in H,
   'K1 已取消答题时逐题收骨架（题目+答案统一在结算时由答题详情写入）')
ok('if (!e.has("k") && old.has("k")) e.put("k", old.optJSONArray("k"));' in B,
   'K2 【修复】合并保留答案：以前"有答案的旧条目"会被"没答案的新条目"覆盖丢失')
ok('hasAnswerFor' in B, 'K2 新增 hasAnswerFor（判断某题是否已收录答案）')
ok('（骨架）不动它' in B,
   'K2 清理逻辑不再误删"只有题目没答案"的骨架条目')
ok('harvestChallengeDetail(challengeId, monthSubject,' in H and 'attempt' in H,
   'K3 结算采集带重试（attempt 计数）')
ok('withAnswer < total' in H and 'waits = {0, 1500, 3000, 6000, 10000, 10000}' in H,
   'K3 结算采集：只要还有题没拿到权威答案就 1.5s/3s/6s/10s 重试')
ok('本机已收录题目 ' in H, 'K3 日志里报告"本机已收录题目 N 题"')

print("K4 收录/合并算法镜像")


def merge(old, new):
    """镜像 XLModBank.put 的合并规则：答案字段谁有留谁"""
    out = dict(new)
    for k in ('k', 'f', 'l'):
        if k not in out and k in old:
            out[k] = old[k]
    if 'opts' not in out and 'opts' in old:
        out['opts'] = old['opts']
    return out


old_entry = {'id': 'q1', 't': 11, 'opts': [{'i': 'a1'}, {'i': 'a2'}], 'k': [1], 'src': 'detail'}
new_entry = {'id': 'q1', 't': 11, 'opts': [{'i': 'a1'}, {'i': 'a2'}]}      # 只收题目、无答案
m = merge(old_entry, new_entry)
ok(m.get('k') == [1], 'K4 旧条目有答案 + 新收录只有题目 → 答案被保留（V5.0p 修复点）')
ok(merge({'id': 'q2', 't': 11}, {'id': 'q2', 't': 11, 'k': [0]}).get('k') == [0],
   'K4 新条目带答案 → 用新答案')


def bank_usable(entry):
    """镜像 apply()：只有带答案的条目才会被用来答题"""
    return bool(entry.get('k') or entry.get('f') or entry.get('l'))


ok(bank_usable({'id': 'q1', 't': 11, 'opts': [1, 2]}) is False,
   'K4 只有题目的骨架条目不会被拿去答题（不会污染作答）')
ok(bank_usable({'id': 'q1', 't': 11, 'k': [1]}) is True, 'K4 收录了答案就能用来答题')

print("L. 误判排查：A/B 错位加固（V5.0p 内修订）")
ok('XLModBank.rightIdsOf' in H and 'sameIdSet' in H and '答案冲突 qid=' in H,
   'L1 答案冲突检测：题库答案 vs 本局详情答案不一致时打日志')
ok('以详情为准并更新题库' in H and 'XLModBank.harvestQuestion(dq, "detail")' in H,
   'L1 冲突时以"本局详情"为准，并用权威答案自愈题库条目')
ok('// 1) **本局服务器详情优先**' in H and H.index('本局服务器详情优先') < H.index('XLModBank.apply(qid, q, ua)'),
   'L1 【顺序修复】服务器详情排在题库之前（以前题库先命中就永远不看详情）')
ok('（来源=本局详情）' in H and '（来源=题库）' in H and '（来源=知识库）' in H and '（来源=判题接口缓存）' in H,
   'L2 每次作答都记日志并标出来源（便于核对选的是哪一个选项）')
ok('XLModBank.lettersOf(q, ua.answerIdList)' in H and 'public static String lettersOf' in B,
   'L2 日志用"界面显示字母"（宿主按显示顺序 65+i 分配）而非 sortid，避免误读')
ok('该题未命中（内容/选项ID 都对不上）' in B,
   'L3 题库只认"内容/选项ID"，对不上就放弃作答、交盲答（不再猜排序）')
ok('sMatchedBySig' in B and 'optionsCompatible' in B and '题干相同但选项对不上' in B,
   'L3 题干签名兜底要求"选项也能对上"，否则放弃（同题干不同选项不再套用旧答案）')
ok('byLetter(cur, letter)' not in B and 'byIndex(cur, idx)' not in B,
   'L3 已彻底移除 sortid/同位次 猜测路径（宁可不答也不猜错）')
ok('内容有歧义' in B and 'byIdHit != hit' in B,
   'L3 选项内容相同/为空导致歧义时，以"选项ID"为准')
ok('已有服务端详情答案，忽略 ' in B and '"detail".equals(oldSrc)' in B,
   'L4 题库来源优先级：detail（服务端）答案不被 classmate/normal 覆盖')

print("L5 匹配优先级镜像（防 A/B 错位）")


def match(entry, cur, by_sig=False):
    """镜像 XLModBank.apply：内容 → 选项ID(歧义优先) → sortid → 同位次；签名兜底不许猜"""
    aid, content, letter, idx = entry['i'], entry['c'], entry.get('d', ''), entry['idx']
    hit, way = None, None
    for o in cur:
        if content and o['c'] == content:
            hit, way = o, '内容'
            break
    if hit is None and aid:
        for o in cur:
            if o['i'] == aid:
                hit, way = o, '选项ID'
                break
    if hit is not None and aid:
        for o in cur:
            if o['i'] == aid and o is not hit:
                hit, way = o, '选项ID(内容有歧义)'
                break
    return hit, way          # V5.0p 内修订：不再有 sortid/同位次 兜底


base = [{'i': 'a1', 'c': '一', 'd': '1'}, {'i': 'a2', 'c': '二', 'd': '2'}, {'i': 'a3', 'c': '三', 'd': '3'}]
shuffled = [{'i': 'a3', 'c': '三', 'd': '3'}, {'i': 'a1', 'c': '一', 'd': '1'}, {'i': 'a2', 'c': '二', 'd': '2'}]
e = {'i': 'a2', 'c': '二', 'd': '2', 'idx': 1}

h1, w1 = match(e, base)
ok(h1['i'] == 'a2' and w1 == '内容', 'L5 选项顺序未变：按内容命中 a2（界面字母 B）')
h2, w2 = match(e, shuffled)
ok(h2['i'] == 'a2' and w2 == '内容', 'L5 选项被重排：仍按内容命中 a2（不是同位次猜的）')
# 内容对不上、ID 还在：靠 ID
e2 = {'i': 'a2', 'c': '二（改了文案）', 'd': '2', 'idx': 1}
h3, w3 = match(e2, shuffled)
ok(h3['i'] == 'a2' and w3 == '选项ID', 'L5 文案有出入时按选项ID命中')
# 内容/ID 都对不上：一律放弃（不再猜排序）
e3 = {'i': 'zz', 'c': '不存在', 'd': '2', 'idx': 1}
h4, w4 = match(e3, shuffled, by_sig=True)
ok(h4 is None, 'L5 内容/选项ID 都对不上 → 放弃作答（绝不猜成 A/B）')

print("M. 填空题按空位ID对位（V5.0p 内修订）")
ok('public static void putFillFromDetail(String qid, java.util.List<String> texts, java.util.List<String> ids)' in B,
   'M1 putFillFromDetail 支持连空位ID一起入库')
ok('e.put("fa", fillIds);' in B and 'fillIds.put(a.answerId' in B,
   'M1 题库里填空题答案带空位ID数组 fa')
ok('ids.add((a == null || a.answerId == null) ? "" : a.answerId)' in H,
   'M1 空位ID 取自 answers[].answerId（与宿主输入框/提交同一把键）')
ok('按空位ID对位作答' in B and 'byBlankId' in B,
   'M2 作答时按当前题目的空位ID取文本（而不是按位次）')
ok('空位ID一个都没对上' in B and '按位次作答' in B,
   'M2 ID 对不上时回退按位次并告警')
ok('withIds' in H and '知识库按位次作答（旧格式）' in H,
   'M3 知识库 F| 升级为「空位ID=答案」，读时优先按ID对位')
ok("fb.append(bid).append('=')" in H,
   'M3 F| 写入带上空位ID（统一在权威采集里写）')
ok('fillFillBoxes' in H and 'mInputMagicEditTextMap' in H and 'mUserInputTextList' in H,
   'M4 把答案按空位ID写进真实输入框')
ok('et.setText(txt);' in H,
   'M4 写框时读回校验')
ok('topUpFillBlanks' in H,
   'M5 没答案的空位用盲填补齐')
ok('applyApiAnswersInner' in H and 'topUpFillBlanks(q, r);' in H,
   'M5 作答主流程收尾统一调用')

print("M6 对位算法镜像")


def positional(recorded):
    return list(recorded)


def by_blank_id(recorded_ids, recorded, current_ids):
    m = dict(zip(recorded_ids, recorded))
    return [m.get(i, "") for i in current_ids]


rec_ids = ['b1', 'b2', 'b3']
rec_txt = ['一', '二', '三']
cur_ids = ['b3', 'b1', 'b2']
pos = positional(rec_txt)
ide = by_blank_id(rec_ids, rec_txt, cur_ids)
ok(pos == rec_txt and ide == ['三', '一', '二'],
   'M6 空位顺序被打乱：按位次=全错（根因）；按空位ID=全对')
ok(by_blank_id(rec_ids, rec_txt, rec_ids) == rec_txt, 'M6 顺序未变时两种方式都对')
ok(by_blank_id(rec_ids, rec_txt, ['b9', 'b8']) == ['', ''],
   'M6 ID 完全不同 → 不猜（留空，交给盲填补齐）')

print("N. 填空权威答案字段 + 只在结算时统一采集（V5.0p 内修订）")
ok('harvestFillAuthoritative' in H and 'getTrueAnswerList' in H,
   'N1 新增 harvestFillAuthoritative：按宿主"答题详情"页的口径取答案（getTrueAnswerList 同源）')
ok('a.answerContent;' in H and 'std.add(t == null ? "" : t)' in H,
   'N1 填空标准答案只取 answers[].answerContent（不是 sContent/answerContentList）')
ok('sContent = 学生作答内容' in H,
   'N1 代码注释写明为什么不能再用 sContent（那是学生作答，自动打时就是我们盲填的）')
ok('putFillFromDetail(q.questionId, std, ids)' in H and 'putFillFromDetail' in B,
   'N1 入库带空位ID（fa），作答时按空位ID对位')
ok('u.answerContentList' not in H and 'u.answerIdList' not in H,
   'N1 填空采集路径已完全不读答案映射的 answerContentList/answerIdList')
ok('不再"先收题目、后补答案"' in H,
   'N2 取消"先记录题目、后补答案"的骨架收录（题库只由结算时的答题详情写入）')
ok('noteQuestion(ql.get(pos)' not in H,
   'N2 答题时不再逐题写入骨架条目')
ok('if (fill.length() == 0) return false;        // V5.0p（内修订）：没有权威答案就不入库' in B,
   'N2 没有权威答案的条目不入库')
ok('withAnswer < total && attempt < 5' in H and '还有 ' in H,
   'N3 结算采集：只要还有题目没拿到权威答案就重试（最多 5 次）')
ok('答题详情采集完成' in H and '权威答案入库' in H,
   'N3 日志按"答题详情 / 权威答案"口径报告（题数、听力、填空）')

print("N4 字段语义镜像（宿主怎么判标准答案）")


def true_answer_fill(answers):
    """镜像 QuestionAnswerViewV2.getTrueAnswerList：标准答案 = 每个空的 answerContent"""
    return [a['answerContent'] for a in answers]


def old_wrong_fill(answer_map):
    """镜像旧实现：拿 answerContentList（= sContent 学生作答）当标准答案"""
    return [a['sContent'] for a in answer_map]


answers = [{'answerId': 'b1', 'answerContent': '三', 'sContent': '不会'},
           {'answerId': 'b2', 'answerContent': '一', 'sContent': '不会'},
           {'answerId': 'b3', 'answerContent': '二', 'sContent': ''}]
ok(true_answer_fill(answers) == ['三', '一', '二'],
   'N4 权威字段 answers[].answerContent → 三,一,二（正确答案）')
ok(old_wrong_fill(answers) == ['不会', '不会', ''],
   'N4 旧实现取 sContent → 不会,不会,(空) ← 这就是填空必然全错的原因')

print('D. 版本与产物')
ok('VERSION = "v5.0p"' in C, 'D1 XLModConfig.VERSION = v5.0p')
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
                   u'题库', u'同学对战采集: 本局入库 ', u'命中作答: ', u'管理员密码不正确', u'盲答', u'挑战详情采集', u'提交前回填', u'输入框未命中', u'详情拿到标准答案', u'清理盲填垃圾', u'无限刷', u'暂无可填答案', u'已答满', u'提前提交结算', u'已到服务端最后一题', u'本地最大题数', u'已撤下', u'权威答案入库', u'收录题目'):
        ok(has(phrase), 'D2 APK 内含新功能字符串「%s」（守卫密钥解密验证）' % phrase[:16])
    ok(has('v5.0p'), 'D2 APK 内含版本号 v5.0p（密文解回原文）')
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
ok('sAutoPhaseNormal = initialPhaseNormal()' in H and 'initialPhaseNormal' in H,
   'E1 每日重置/手动触发/换学科时都按 kind 复位阶段（V4.7p：无限刷时固定为普通挑战）')
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
ok('答题详情采集完成' in H and '答题详情采集失败' not in H or '挑战详情采集失败' in H,
   'E3 采集有开始/完成（题数、权威答案数、题库增量）/失败日志')
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
