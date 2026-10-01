# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

"""签发 / 校验远程授权配置（V4.3p）。

**配置一直是明文的**：作者本地 `features.json` 直接用记事本改，改完签发 → 生成仓库里唯一的
授权文件 `license.json`（配置 + 签名打包在一起）。**明文的 features.json 不上传仓库。**

license.json 结构（单文件，避免 CDN 上"配置/签名两份缓存"不同步）：

    {"payload": "<base64(配置原始字节)>", "sig": "<base64(ECDSA P-256 签名)>"}

**管理员密码**同样是配置里的一个字段：

    features.json:   "admin_password": "你的密码"
    签发后的 payload: "admin": {"alg":"PBKDF2-HMAC-SHA256","iters":20000,"salt":"…","hash":"…"}

也就是：密码留在你本地明文配置里；**上传到仓库的那份只有 PBKDF2 校验块，没有密码明文**。
（`salt` 由密码派生，同一密码重复签发的结果稳定，`sign --skip-if-same` 依然有效。）

用法：

    python sign_config.py sign       features.json    # 生成 features.json.sig（旧式两份文件，可选）
    python sign_config.py verify     features.json    # 校验签名
    python sign_config.py bundle     features.json    # ★ 生成 license.json（含 admin 校验块换算）
    python sign_config.py check      license.json     # 校验 license.json（验签 + 摘要）
    python sign_config.py show-pw    license.json     # 看授权里 admin 块的信息（不显示密码）
    python sign_config.py verify-pw  密码 [license]    # 像端上一样校验管理员密码
    python sign_config.py set-pw     新密码 [features] # 把 admin_password 写进本地 features.json

私钥**绝不要**提交到仓库（`.gitignore` 已排除 keys/）。
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

# openssl 可执行文件：优先环境变量 XLMod_OPENSSL（Windows 下 cmd 的 PATH 里通常没有 openssl，
# 但 Git for Windows 自带；update_license.bat 会自动探测并设置该变量）
OPENSSL = os.environ.get('XLMod_OPENSSL') or 'openssl'

ADMIN_ITERS = 20000
ADMIN_SALT_LABEL = b'XLModAdmin|'


def _find_key(name):
    """在 keys/、../keys/、%XLMod_KEYS% 里找文件（仓库里只有公钥，私钥在作者本地）"""
    cands = [os.path.join('keys', name),
             os.path.join('..', 'keys', name),
             os.path.join(os.environ.get('XLMod_KEYS', ''), name) if os.environ.get('XLMod_KEYS') else None]
    for c in cands:
        if c and os.path.exists(c):
            return c
    return os.path.join('keys', name)      # 默认路径（报错信息更直观）


PUB = _find_key('xlmod_config_ec_public.pem')
PRIV = _find_key('xlmod_config_ec_private.pem')


def _normalize_lf(path):
    """把配置规范成 LF 行尾：git 入库会把 CRLF 转 LF；
    若签名用的是 CRLF 字节，仓库里的明文配置就会验签失败（回退路径）。"""
    data = open(path, 'rb').read()
    data = data.replace(b'\r\n', b'\n').replace(b'\r', b'\n')
    open(path, 'wb').write(data)


def _sign_bytes(data):
    if not os.path.exists(PRIV):
        raise SystemExit('找不到私钥：%s\n（私钥只在作者本机，别提交到仓库）' % PRIV)
    tmp_in, tmp_sig = '_sign_in.bin', '_sign_out.sig'
    open(tmp_in, 'wb').write(data)
    try:
        subprocess.run([OPENSSL, 'dgst', '-sha256', '-sign', PRIV, '-out', tmp_sig, tmp_in], check=True)
        return open(tmp_sig, 'rb').read()
    finally:
        for f in (tmp_in, tmp_sig):
            if os.path.exists(f):
                os.remove(f)


def _verify_bytes(data, sig):
    tmp_in, tmp_sig = '_v_in.bin', '_v_out.sig'
    open(tmp_in, 'wb').write(data)
    open(tmp_sig, 'wb').write(sig)
    try:
        r = subprocess.run([OPENSSL, 'dgst', '-sha256', '-verify', PUB, '-signature', tmp_sig, tmp_in],
                           capture_output=True)
        return r.returncode == 0
    finally:
        for f in (tmp_in, tmp_sig):
            if os.path.exists(f):
                os.remove(f)


# ==================== 管理员密码（配置字段） ====================

def admin_block(password):
    """把明文密码换算成 PBKDF2 校验块（salt 由密码派生 → 同一密码结果稳定）"""
    pw = password.strip()
    salt = hashlib.sha256(ADMIN_SALT_LABEL + pw.encode('utf-8')).digest()[:16]
    h = hashlib.pbkdf2_hmac('sha256', pw.encode('utf-8'), salt, ADMIN_ITERS, 32)
    return {'alg': 'PBKDF2-HMAC-SHA256', 'iters': ADMIN_ITERS,
            'salt': base64.b64encode(salt).decode(), 'hash': base64.b64encode(h).decode()}


def payload_from_cfg(cfg_path):
    """读明文配置 → 产出**将要签名的 payload 字节**：
    把 admin_password（明文，只在你本地）换成 admin（PBKDF2 校验块），其余字段原样保留。"""
    _normalize_lf(cfg_path)
    cfg = json.load(io.open(cfg_path, encoding='utf-8'))
    pw = (cfg.pop('admin_password', '') or '').strip()
    if pw:
        cfg['admin'] = admin_block(pw)
        print('已把 admin_password 换算成 PBKDF2 校验块（%d 次迭代，盐由密码派生）' % ADMIN_ITERS)
    elif not cfg.get('admin'):
        print('[提示] 配置里没有 admin_password -> 授权里不含管理员密码，端上无法解锁管理员模式')
    raw = json.dumps(cfg, ensure_ascii=False, indent=2).encode('utf-8')
    return cfg, raw


# ==================== 签发 ====================

def sign(path, skip_if_same=False):
    """旧式两份文件：features.json + features.json.sig（端上有回退读取，仓库不再放）"""
    sig_path = path + '.sig'
    sha_path = path + '.sha256'
    _normalize_lf(path)
    cur = hashlib.sha256(open(path, 'rb').read()).hexdigest()
    if skip_if_same and os.path.exists(sig_path) and os.path.exists(sha_path):
        if open(sha_path).read().strip() == cur:
            print('配置未改动（sha256 %s…），跳过重新签名' % cur[:12])
            return
    raw = open(path, 'rb').read()
    sig = _sign_bytes(raw)
    open(sig_path, 'wb').write(base64.b64encode(sig))
    open(sha_path, 'w').write(cur + '\n')
    print('已签名: %s (%d 字节签名)' % (sig_path, len(sig)))
    print('配置 sha256: %s' % cur)


def verify(path):
    sig_path = path + '.sig'
    if not os.path.exists(sig_path):
        raise SystemExit('缺少 %s' % sig_path)
    ok = _verify_bytes(open(path, 'rb').read(), base64.b64decode(open(sig_path).read().strip()))
    print(('验签通过: ' if ok else '验签失败: ') + path)
    if not ok:
        sys.exit(1)


def bundle(path='features.json', out='license.json'):
    """★ 生成仓库里唯一的授权文件：配置（含 admin 校验块）+ 签名，单文件。"""
    cfg, raw = payload_from_cfg(path)
    sig = _sign_bytes(raw)
    doc = {'payload': base64.b64encode(raw).decode(), 'sig': base64.b64encode(sig).decode()}
    text = json.dumps(doc, ensure_ascii=False, indent=2)
    with io.open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('已生成 %s（%d 字节；payload=%d 字节；含管理员校验块=%s）'
          % (out, len(text), len(raw), '是' if cfg.get('admin') else '否'))
    print('明文 features.json 不上传仓库；仓库里只有这份签名后的 license.json')
    check(out, quiet=False)


def payload_of(path='license.json'):
    o = json.load(io.open(path, encoding='utf-8'))
    return base64.b64decode(o['payload']), base64.b64decode(o['sig'])


def check(path='license.json', quiet=False):
    raw, sig = payload_of(path)
    if not _verify_bytes(raw, sig):
        print('授权验签失败: %s' % path)
        sys.exit(1)
    if quiet:
        return
    cfg = json.loads(raw.decode('utf-8'))
    adm = cfg.get('admin') or {}
    print('授权校验通过: %s' % path)
    print('  已开启  : %s' % [k for k, v in (cfg.get('features') or {}).items() if v])
    print('  关闭    : %s' % [k for k, v in (cfg.get('features') or {}).items() if not v])
    print('  kill    : %s · 有效期: %s' % (cfg.get('kill'), cfg.get('expires') or '长期'))
    print('  公告    : %s' % (cfg.get('notice') or '(无)'))
    print('  管理员  : %s' % ('%s · %d 次迭代（无密码明文）' % (adm.get('alg'), adm.get('iters', 0))
                              if adm.get('hash') else '(无：端上不能解锁管理员模式)'))
    if 'admin_password' in raw.decode('utf-8'):
        print('  [警告] payload 里出现了 admin_password 明文（应该只留 admin 校验块）')


def verify_pw(pw, path='license.json'):
    """像端上一样校验管理员密码（对着 license.json 里的 admin 块）"""
    raw, _ = payload_of(path)
    cfg = json.loads(raw.decode('utf-8'))
    adm = cfg.get('admin') or {}
    plain = (cfg.get('admin_password') or '').strip()
    if plain:
        ok = (pw.strip() == plain)
        print('管理员密码校验（明文比对）：%s' % ('通过' if ok else '不匹配'))
        sys.exit(0 if ok else 1)
    if not adm.get('hash'):
        print('这份授权里没有管理员密码字段')
        sys.exit(1)
    got = hashlib.pbkdf2_hmac('sha256', pw.strip().encode('utf-8'), base64.b64decode(adm['salt']),
                              int(adm.get('iters', ADMIN_ITERS)), 32)
    ok = hmac.compare_digest(got, base64.b64decode(adm['hash']))
    print('管理员密码校验：%s' % ('通过（端上会放行全部功能）' if ok else '不匹配'))
    sys.exit(0 if ok else 1)


def set_pw(pw, cfg_path='features.json'):
    """把管理员密码写进**本地明文配置**（下次 bundle 生效）"""
    if not pw:
        raise SystemExit('用法: python sign_config.py set-pw 你的管理员密码 [features.json]')
    if not os.path.exists(cfg_path):
        raise SystemExit('找不到 %s' % cfg_path)
    text = io.open(cfg_path, encoding='utf-8').read()
    cfg = json.loads(text)
    cfg['admin_password'] = pw.strip()
    with io.open(cfg_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(cfg, ensure_ascii=False, indent=2) + '\n')
    print('已写入 admin_password 到 %s（明文只在你本地）' % cfg_path)
    print('接着跑：python sign_config.py bundle %s  或  python publish_license.py' % cfg_path)


def show_pw(path='license.json'):
    raw, _ = payload_of(path)
    cfg = json.loads(raw.decode('utf-8'))
    adm = cfg.get('admin') or {}
    if adm.get('hash'):
        print('授权里的管理员密码：PBKDF2-HMAC-SHA256 · %d 次迭代 · 盐 %s… · 哈希 %s…（没有密码明文）'
              % (adm.get('iters', 0), (adm.get('salt') or '')[:12], (adm.get('hash') or '')[:12]))
    elif cfg.get('admin_password'):
        print('授权里直接写了 admin_password 明文（建议改用 set-pw 走校验块）')
    else:
        print('授权里没有管理员密码字段')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    cmd = sys.argv[1]
    args = [a for a in sys.argv[2:] if not a.startswith('-')]
    flags = [a for a in sys.argv[2:] if a.startswith('-')]
    if cmd == 'sign':
        sign(args[0] if args else 'features.json', '--skip-if-same' in flags)
    elif cmd == 'verify':
        verify(args[0] if args else 'features.json')
    elif cmd == 'bundle':
        bundle(args[0] if args else 'features.json', args[1] if len(args) > 1 else 'license.json')
    elif cmd in ('check', 'check-bundle'):
        check(args[0] if args else 'license.json')
    elif cmd == 'show-pw':
        show_pw(args[0] if args else 'license.json')
    elif cmd == 'set-pw':
        set_pw(args[0] if args else '', args[1] if len(args) > 1 else 'features.json')
    elif cmd == 'verify-pw':
        if not args:
            raise SystemExit('用法: python sign_config.py verify-pw 密码 [license.json]')
        verify_pw(args[0], args[1] if len(args) > 1 else 'license.json')
    else:
        raise SystemExit('未知命令: %s\n%s' % (cmd, __doc__))
