# -*- coding: utf-8 -*-
"""推送前自检：确认待推送目录里**只有开源内容**，没有宿主 App 的代码/产物。
   检查项：①禁止的扩展名/目录 ②是否含原版包名路径 ③AGPL 头覆盖率 ④必要文件齐全
          ⑤授权只上密文（license.json 无明文、无旧版 features.json）
          ⑥不含私钥/主密钥/管理员密码，且端上密钥是占位值
"""
import io, os, sys, re, json, base64

REPO = 'tmp-repo'
problems = []


def ok(cond, msg):
    print(("  [OK]  " if cond else "  [FAIL] ") + msg)
    if not cond:
        problems.append(msg)


files = []
for root, dirs, names in os.walk(REPO):
    dirs[:] = [d for d in dirs if d != '.git']
    for n in names:
        files.append(os.path.join(root, n).replace('\\', '/'))

print("推送目录共 %d 个文件" % len(files))

print("① 禁止的内容（宿主 App 代码/产物）")
bad_ext = ('.smali', '.dex', '.apk', '.jar', '.class', '.so', '.zip')
hits = [f for f in files if f.lower().endswith(bad_ext)]
ok(not hits, "无 smali/dex/apk/jar/class/so/zip（命中: %s）" % (hits or '无'))
bad_dirs = ('apktool-out', 'jadx-out', 'jadx/', 'obf-src', 'obf-classes', 'obf-dex', 'guard-classes',
            'guard-dex', 'evidence', 'mod-stub-classes')
hits2 = [f for f in files if any(('/' + d) in f or f.startswith(d) for d in bad_dirs)]
ok(not hits2, "无反编译/中间产物目录（命中: %s）" % (hits2 or '无'))

print("② 是否夹带原版代码")
# 源码（.java/.py）里不允许出现宿主 App 的包路径
app_pkgs = ['net/xuele/xuelets/homework/', 'net/xuele/xuelets/challenge/', 'net/xuele/im/',
            'net/xuele/android/']
hits3 = []
for f in files:
    if not (f.endswith('.java') or f.endswith('.py')):
        continue
    if f.endswith('precheck_push.py'):
        continue          # 本检查器自身就含这些特征串（模式表），跳过自己
    s = io.open(f, encoding='utf-8').read()
    for p in app_pkgs:
        if p in s:
            hits3.append((f, p))
ok(not hits3, "源码中无宿主 App 包路径（命中: %s）" % (hits3[:3] or '无'))

# 文档里引用"类名/方法名"是允许的（注入点清单），但不允许出现 smali 代码特征
smali_marks = ['invoke-virtual', 'invoke-static', 'iget-object', 'const-string v', '.method ',
               'iput-object', 'move-result-object']
hits4 = []
for f in files:
    if not f.endswith('.md'):
        continue
    s = io.open(f, encoding='utf-8').read()
    for m in smali_marks:
        if m in s:
            hits4.append((f, m))
ok(not hits4, "文档中无 smali 代码片段（命中: %s）" % (hits4[:3] or '无'))

print("③ AGPL 头覆盖率")
java_files = [f for f in files if f.endswith('.java')]
spdx = [f for f in java_files if 'SPDX-License-Identifier: AGPL-3.0-or-later' in io.open(f, encoding='utf-8').read()]
ok(len(spdx) == len(java_files), "全部 Java 文件含 SPDX 头（%d/%d）" % (len(spdx), len(java_files)))
py_files = [f for f in files if f.endswith('.py')]
spdx_py = [f for f in py_files if 'SPDX-License-Identifier: AGPL-3.0-or-later' in io.open(f, encoding='utf-8').read()]
ok(len(spdx_py) == len(py_files), "全部脚本含 SPDX 头（%d/%d）" % (len(spdx_py), len(py_files)))

print("④ 必要文件")
need = ['LICENSE', 'README.md', 'NOTICE.md', '.gitignore', 'license.json',
        'mod-src/net/xuele/xuelets/mod/XLModFeatures.java',
        'mod-src/net/xuele/xuelets/mod/XLModSecrets.java',
        'mod-src/net/xuele/xuelets/mod/XLModBank.java',
        'mod-src/net/xuele/xuelets/mod/XLModCrypto.java',
        'mod-src/net/xuele/xuelets/mod/Obf.java',
        'guard_gen.py', 'build_guard.py', 'obf_strings.py', 'inject_dexes.py',
        'obf-rules.pro', 'guard-rules.pro',
        'docs/REMOTE_CONFIG.md', 'docs/MULTIDEX_INTERLOCK.md', 'docs/CLOUD_KEEP_ORIGINAL_DESIGN.md',
        'docs/OPEN_SOURCE_SCOPE.md', 'docs/INJECTION_POINTS.md']
for n in need:
    ok(os.path.exists(os.path.join(REPO, n)), "存在 %s" % n)

print("⑤ 授权只上密文（V4.3p）")
lic_path = os.path.join(REPO, 'license.json')
if os.path.exists(lic_path):
    txt = io.open(lic_path, encoding='utf-8').read()
    try:
        o = json.loads(txt)
    except Exception as e:
        o = {}
        ok(False, "license.json 不是合法 JSON：%s" % e)
    ok('enc' in o and 'salt' in o and 'sig' in o, "license.json 是加密格式（enc/salt/sig）")
    ok('payload' not in o, "license.json 里没有旧版明文 payload")
    for leak in ('"features"', '"notice"', 'identity', 'homework', 'admin_hash'):
        ok(leak not in txt, "license.json 不含明文片段 %s" % leak)
    try:
        blob = base64.b64decode(o.get('enc', ''))
        ok(len(blob) > 32, "密文体量正常（%d 字节）" % len(blob))
    except Exception:
        ok(False, "enc 不是合法 base64")
for legacy in ('features.json', 'features.json.sig', 'features.json.sha256'):
    ok(not os.path.exists(os.path.join(REPO, legacy)), "仓库里没有明文 %s（license 明文不上仓库）" % legacy)

print("⑥ 密钥/密码不泄露")
priv = [f for f in files if f.endswith('.pem') and 'private' in f.lower()]
ok(not priv, "仓库中没有私钥（命中: %s）" % (priv or '无'))
ok(not os.path.exists(os.path.join(REPO, 'keys', 'license_key.txt')), "仓库中没有主密钥 keys/license_key.txt")
ok(not os.path.exists(os.path.join(REPO, 'keys', 'admin_pw.txt')), "仓库中没有管理员密码 keys/admin_pw.txt")
pub = [f for f in files if f.endswith('.pem') and 'public' in f.lower()]
ok(bool(pub), "包含公钥文件（供校验用）: %s" % (pub or '无'))
sec = os.path.join(REPO, 'mod-src/net/xuele/xuelets/mod/XLModSecrets.java')
if os.path.exists(sec):
    s = io.open(sec, encoding='utf-8').read()
    m = re.search(r'LICENSE_KEY_HEX\s*=\s*\n?\s*"([0-9a-fA-F]{64})"', s)
    ok(bool(m) and m.group(1).lower() == '0' * 0 + '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f',
       "端上主密钥是仓库占位值（真实主密钥未被提交）")

APP = re.compile(r'(net\.xuele\.(android|im)\.|SingleFileTask|FileUploadManager|AssignHomeworkActivity|AssignWorkHelper|LoginManager|VideoUtils|VideoFormatHelper)')
bad_docs = []
for f in files:
    if f.endswith('.md'):
        if APP.search(io.open(f, encoding='utf-8').read()):
            bad_docs.append(f)
ok(not bad_docs, "文档已降粒度（无宿主类名，命中: %s）" % (bad_docs or '无'))

print("=" * 60)
if problems:
    print("存在问题：")
    for p in problems:
        print("  -", p)
    sys.exit(1)
print("推送前自检通过：目录中只有 XLMod 自有代码与文档（AGPL-3.0），授权只上密文")

