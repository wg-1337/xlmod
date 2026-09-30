# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

"""签发 / 校验远程授权配置（V4.3p 起：**加密授权**）。

V4.3p 的 license.json 里**只有密文**（仓库里看不到功能开关，也看不到管理员密码）：

    {"v":2, "alg":"AES-256-CBC", "salt":"<base64>", "enc":"<base64 iv||密文>", "sig":"<base64>"}

  · 明文 = 原来的 features.json（features / notice / expires / kill）+ `admin` 管理员密码校验块；
  · AES 密钥 = HMAC-SHA256(主密钥, salt + "XLModLic-v2")，主密钥在 `keys/license_key.txt`（**不提交**）；
  · 签名 = ECDSA P-256（SHA256withECDSA）对 `XLModLic-v2|<salt>|<enc>` 的 UTF-8 字节签名（作者私钥）。
  · `admin` = {"alg":"PBKDF2-HMAC-SHA256","iters":20000,"salt":...,"hash":...}
    —— 面板输入的管理员密码与它比对；正确 → 端上"管理员解锁"，全部功能放行。

用法（签发，需要 `keys/xlmod_config_ec_private.pem` 与 `keys/license_key.txt`）：

    python sign_config.py init-key                 # ① 生成主密钥并写回 XLModSecrets.java（一次即可）
    python sign_config.py set-pw 你的管理员密码     # ② 设置管理员密码（写 keys/admin_pw.txt）
    python sign_config.py seal features.json       # ③ 加密+签名 → license.json（仓库只提交它）
    python sign_config.py check license.json       # ④ 校验（验签 + 解密 + 打印摘要）
    python sign_config.py open  license.json       # 解出明文（本地看配置）
    python sign_config.py verify-pw 密码            # 像端上一样校验管理员密码

旧版明文授权（V4.2 及以前）的工具仍然保留：

    python sign_config.py sign   features.json     # 生成 features.json.sig
    python sign_config.py verify features.json     # 校验签名
    python sign_config.py bundle features.json     # 打包成明文版 license.json
    python sign_config.py check-bundle license.json

私钥与主密钥**绝不要**提交到仓库（.gitignore 已排除 keys/ 下的 *.pem / *.key / license_key.txt / admin_pw.txt）。
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

SIG_PREFIX = b'XLModLic-v2|'
MASTER_LABEL = b'XLModLic-v2'
ADMIN_ITERS = 20000
SECRETS_JAVA = os.path.join('mod-src', 'net', 'xuele', 'xuelets', 'mod', 'XLModSecrets.java')


def _find_key(name):
    """在 keys/、../keys/、%XLMod_KEYS% 里找文件（仓库里只有公钥，私钥/主密钥在作者本地）"""
    cands = [os.path.join('keys', name),
             os.path.join('..', 'keys', name),
             os.path.join(os.environ.get('XLMod_KEYS', ''), name) if os.environ.get('XLMod_KEYS') else None]
    for c in cands:
        if c and os.path.exists(c):
            return c
    return os.path.join('keys', name)      # 默认路径（报错信息更直观）


PUB = _find_key('xlmod_config_ec_public.pem')
PRIV = _find_key('xlmod_config_ec_private.pem')
MASTER = _find_key('license_key.txt')
ADMIN_PW = _find_key('admin_pw.txt')


def _b64e(b):
    return base64.b64encode(b).decode('ascii')


def _b64d(s):
    return base64.b64decode(s)


def _normalize_lf(path):
    """把配置规范成 LF 行尾：git 入库会把 CRLF 转 LF；
    若签名用的是 CRLF 字节，仓库里的明文配置就会验签失败（回退路径）。"""
    data = open(path, 'rb').read()
    data = data.replace(b'\r\n', b'\n').replace(b'\r', b'\n')
    open(path, 'wb').write(data)


# ==================== 旧版：明文签名（兼容保留） ====================

def sign(path, skip_if_same=False):
    sig_path = path + '.sig'
    sha_path = path + '.sha256'
    _normalize_lf(path)
    cur = hashlib.sha256(open(path, 'rb').read()).hexdigest()
    if skip_if_same and os.path.exists(sig_path) and os.path.exists(sha_path):
        if open(sha_path).read().strip() == cur:
            print('配置未改动（sha256 %s…），跳过重新签名' % cur[:12])
            return
    if not os.path.exists(PRIV):
        raise SystemExit('找不到私钥：%s\n（私钥只在作者本机，别提交到仓库）' % PRIV)
    subprocess.run([OPENSSL, 'dgst', '-sha256', '-sign', PRIV, '-out', sig_path + '.bin', path], check=True)
    with open(sig_path + '.bin', 'rb') as f:
        raw = f.read()
    os.remove(sig_path + '.bin')
    with open(sig_path, 'w') as f:
        f.write(base64.b64encode(raw).decode())
    open(sha_path, 'w').write(cur + '\n')
    print('已签名: %s (%d 字节签名)' % (sig_path, len(raw)))
    print('配置 sha256: %s' % cur)


def verify(path):
    sig_path = path + '.sig'
    if not os.path.exists(sig_path):
        raise SystemExit('缺少 %s' % sig_path)
    raw = base64.b64decode(open(sig_path).read().strip())
    with open(sig_path + '.bin', 'wb') as f:
        f.write(raw)
    r = subprocess.run([OPENSSL, 'dgst', '-sha256', '-verify', PUB, '-signature', sig_path + '.bin', path],
                       capture_output=True)
    os.remove(sig_path + '.bin')
    ok = (r.returncode == 0)
    print(('验签通过: ' if ok else '验签失败: ') + path)
    if not ok:
        sys.stdout.write(r.stdout.decode('utf-8', 'replace') + r.stderr.decode('utf-8', 'replace'))
        sys.exit(1)


def bundle(path):
    """把 配置 + 签名 打成一个**明文** license.json（旧格式，V4.2 及以前；端上仍兼容读取）。
       结构：{"payload":"<base64(配置原始字节)>","sig":"<base64(签名)>"}
       注意：以 LF 写出 —— git 入库会把 CRLF 转 LF，写 CRLF 会造成"每次运行都像有改动"的噪音。
    """
    raw = open(path, 'rb').read()
    sig_path = path + '.sig'
    if not os.path.exists(sig_path):
        raise SystemExit('缺少 %s（先 sign）' % sig_path)
    sig = open(sig_path).read().strip()
    out = json.dumps({'payload': base64.b64encode(raw).decode(), 'sig': sig},
                     ensure_ascii=False, indent=2)
    with open('license.json', 'w', encoding='utf-8', newline='\n') as f:
        f.write(out)
    print('已生成 license.json（%d 字节，payload=%d 字节，明文格式）' % (len(out), len(raw)))


# ==================== V4.3p：加密授权 ====================

def load_master(required=True):
    """读取本地主密钥（hex → bytes）"""
    if os.path.exists(MASTER):
        s = io.open(MASTER, encoding='utf-8').read().strip()
        s = re.sub(r'[^0-9a-fA-F]', '', s)
        if len(s) >= 64:
            return bytes.fromhex(s[:64])
        raise SystemExit('主密钥长度不对（需要 64 个 hex 字符）：%s' % MASTER)
    if required:
        raise SystemExit('找不到主密钥 %s\n先运行：python sign_config.py init-key' % MASTER)
    return None


def load_admin_pw(required=False):
    if os.path.exists(ADMIN_PW):
        pw = io.open(ADMIN_PW, encoding='utf-8').read().strip()
        if pw:
            return pw
    if required:
        raise SystemExit('找不到管理员密码 %s\n先运行：python sign_config.py set-pw 你的密码' % ADMIN_PW)
    return None


def derive_key(master, salt):
    """与端上 XLModCrypto.licenseKey() 完全一致：HMAC-SHA256(主密钥, salt + 标签)"""
    return hmac.new(master, salt + MASTER_LABEL, hashlib.sha256).digest()


def admin_block(pw):
    salt = os.urandom(16)
    h = hashlib.pbkdf2_hmac('sha256', pw.encode('utf-8'), salt, ADMIN_ITERS, 32)
    return {'alg': 'PBKDF2-HMAC-SHA256', 'iters': ADMIN_ITERS,
            'salt': _b64e(salt), 'hash': _b64e(h)}


def _openssl_enc(key, iv, data):
    """AES-256-CBC + PKCS7（openssl 默认填充），-nosalt 表示不加盐头 -> 与端上 Cipher 一致"""
    tmp_in, tmp_out = '_seal_in.bin', '_seal_out.bin'
    open(tmp_in, 'wb').write(data)
    try:
        subprocess.run([OPENSSL, 'enc', '-aes-256-cbc', '-nosalt',
                        '-K', key.hex(), '-iv', iv.hex(),
                        '-in', tmp_in, '-out', tmp_out], check=True, capture_output=True)
        return open(tmp_out, 'rb').read()
    finally:
        for f in (tmp_in, tmp_out):
            if os.path.exists(f):
                os.remove(f)


def _openssl_dec(key, iv, data):
    tmp_in, tmp_out = '_seal_in.bin', '_seal_out.bin'
    open(tmp_in, 'wb').write(data)
    try:
        subprocess.run([OPENSSL, 'enc', '-d', '-aes-256-cbc', '-nosalt',
                        '-K', key.hex(), '-iv', iv.hex(),
                        '-in', tmp_in, '-out', tmp_out], check=True, capture_output=True)
        return open(tmp_out, 'rb').read()
    finally:
        for f in (tmp_in, tmp_out):
            if os.path.exists(f):
                os.remove(f)


def _sign_bytes(data):
    if not os.path.exists(PRIV):
        raise SystemExit('找不到私钥：%s\n（私钥只在作者本机，别提交到仓库）' % PRIV)
    tmp_in, tmp_sig = '_seal_sign.bin', '_seal_sign.sig'
    open(tmp_in, 'wb').write(data)
    try:
        subprocess.run([OPENSSL, 'dgst', '-sha256', '-sign', PRIV, '-out', tmp_sig, tmp_in], check=True)
        return open(tmp_sig, 'rb').read()
    finally:
        for f in (tmp_in, tmp_sig):
            if os.path.exists(f):
                os.remove(f)


def _verify_bytes(data, sig):
    tmp_in, tmp_sig = '_seal_v.bin', '_seal_v.sig'
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


def seal(cfg_path='features.json', out='license.json'):
    """加密 + 签名：明文配置 → 只有密文的 license.json（仓库里只有它，没有明文）"""
    master = load_master()
    pw = load_admin_pw(required=False)
    cfg = json.load(io.open(cfg_path, encoding='utf-8'))
    if pw:
        cfg['admin'] = admin_block(pw)
        print('已写入管理员密码校验块（PBKDF2-HMAC-SHA256, %d 次迭代）' % ADMIN_ITERS)
    else:
        print('[警告] 没有 keys/admin_pw.txt -> license 里不含管理员密码块，端上无法解锁管理员模式')
    raw = json.dumps(cfg, ensure_ascii=False, indent=2).encode('utf-8')
    salt = os.urandom(16)
    iv = os.urandom(16)
    key = derive_key(master, salt)
    ct = _openssl_enc(key, iv, raw)
    salt_b64, enc_b64 = _b64e(salt), _b64e(iv + ct)
    signed = SIG_PREFIX + salt_b64.encode('ascii') + b'|' + enc_b64.encode('ascii')
    sig = _sign_bytes(signed)
    doc = {'v': 2, 'alg': 'AES-256-CBC/PBKDF2-HMAC-SHA256/ECDSA-P256',
           'salt': salt_b64, 'enc': enc_b64, 'sig': _b64e(sig)}
    text = json.dumps(doc, ensure_ascii=False, indent=2)
    with io.open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('已生成加密授权 %s（%d 字节；明文 %d 字节；AES-256-CBC + ECDSA P-256）'
          % (out, len(text), len(raw)))
    print('仓库里只有密文：看不到 features，也看不到管理员密码')
    # 立刻自检一次（避免发出解不开的授权）
    check(out, quiet=False)


def open_license(path='license.json'):
    o = json.load(io.open(path, encoding='utf-8'))
    if 'enc' not in o:
        raw = _b64d(o['payload'])
        print(raw.decode('utf-8'))
        return raw
    master = load_master()
    salt = _b64d(o['salt'])
    blob = _b64d(o['enc'])
    raw = _openssl_dec(derive_key(master, salt), blob[:16], blob[16:])
    print(raw.decode('utf-8'))
    return raw


def check(path='license.json', quiet=False):
    o = json.load(io.open(path, encoding='utf-8'))
    if 'enc' in o:
        signed = SIG_PREFIX + o['salt'].encode('ascii') + b'|' + o['enc'].encode('ascii')
        if not _verify_bytes(signed, _b64d(o['sig'])):
            print('加密授权验签失败: %s' % path)
            sys.exit(1)
        master = load_master(required=False)
        if master is None:
            print('加密授权验签通过（本机没有主密钥，跳过解密校验）: %s' % path)
            return
        salt = _b64d(o['salt'])
        blob = _b64d(o['enc'])
        try:
            raw = _openssl_dec(derive_key(master, salt), blob[:16], blob[16:])
            cfg = json.loads(raw.decode('utf-8'))
        except Exception as e:
            print('加密授权解密失败（主密钥不匹配？）: %s' % e)
            sys.exit(1)
        if quiet:
            return
        adm = cfg.get('admin') or {}
        print('加密授权校验通过: %s' % path)
        print('  已开启  : %s' % [k for k, v in (cfg.get('features') or {}).items() if v])
        print('  关闭    : %s' % [k for k, v in (cfg.get('features') or {}).items() if not v])
        print('  kill    : %s · 有效期: %s' % (cfg.get('kill'), cfg.get('expires') or '长期'))
        print('  公告    : %s' % (cfg.get('notice') or '(无)'))
        print('  管理员块: %s' % ('有（%s, %d 次迭代）' % (adm.get('alg'), adm.get('iters', 0))
                                  if adm.get('hash') else '无'))
        return
    # 旧格式
    raw = _b64d(o['payload'])
    if not _verify_bytes(raw, _b64d(o['sig'])):
        print('明文授权验签失败: %s' % path)
        sys.exit(1)
    if not quiet:
        cfg = json.loads(raw.decode('utf-8'))
        print('明文授权校验通过: %s' % path)
        print('  已开启  : %s' % [k for k, v in (cfg.get('features') or {}).items() if v])


def verify_pw(pw, path='license.json'):
    """像端上一样校验管理员密码（对着 license 里的 admin 校验块）"""
    o = json.load(io.open(path, encoding='utf-8'))
    if 'enc' in o:
        master = load_master()
        salt = _b64d(o['salt'])
        blob = _b64d(o['enc'])
        cfg = json.loads(_openssl_dec(derive_key(master, salt), blob[:16], blob[16:]).decode('utf-8'))
    else:
        cfg = json.loads(_b64d(o['payload']).decode('utf-8'))
    adm = cfg.get('admin') or {}
    if not adm.get('hash'):
        print('这份授权里没有管理员密码块')
        sys.exit(1)
    got = hashlib.pbkdf2_hmac('sha256', pw.encode('utf-8'), _b64d(adm['salt']),
                              int(adm.get('iters', ADMIN_ITERS)), 32)
    ok = hmac.compare_digest(got, _b64d(adm['hash']))
    print('管理员密码校验：%s' % ('通过（端上会放行全部功能）' if ok else '不匹配'))
    sys.exit(0 if ok else 1)


def init_key(quiet=False):
    """生成本地主密钥，并写回 XLModSecrets.java（端上用它解密授权；仓库里是占位值）"""
    if os.path.exists(MASTER) and not quiet:
        print('[提示] 主密钥已存在，保持原样：%s（要更换请先删除该文件）' % MASTER)
        return load_master()
    key = os.urandom(32)
    os.makedirs(os.path.dirname(MASTER), exist_ok=True)
    io.open(MASTER, 'w', encoding='utf-8', newline='\n').write(key.hex() + '\n')
    print('已生成主密钥: %s' % MASTER)
    if os.path.exists(SECRETS_JAVA):
        s = io.open(SECRETS_JAVA, encoding='utf-8').read()
        s2 = re.sub(r'LICENSE_KEY_HEX\s*=\s*\n?\s*"[0-9a-fA-F]{64}"',
                    'LICENSE_KEY_HEX =\n            "%s"' % key.hex(), s)
        if s2 == s:
            print('[警告] 未能自动写入 %s（请手工把主密钥填进 LICENSE_KEY_HEX）' % SECRETS_JAVA)
        else:
            io.open(SECRETS_JAVA, 'w', encoding='utf-8', newline='\n').write(s2)
            print('已写入端上主密钥: %s' % SECRETS_JAVA)
    else:
        print('[警告] 没找到 %s（请确认在仓库根目录运行）' % SECRETS_JAVA)
    return key


def set_pw(pw):
    if not pw:
        raise SystemExit('用法: python sign_config.py set-pw 你的管理员密码')
    io.open(ADMIN_PW, 'w', encoding='utf-8', newline='\n').write(pw.strip() + '\n')
    print('已保存管理员密码到 %s（不提交到仓库）' % ADMIN_PW)
    print('接着跑：python sign_config.py seal features.json 重新签发 license.json')


def check_bundle(path='license.json'):
    check(path)


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    cmd = sys.argv[1]
    args = [a for a in sys.argv[2:] if not a.startswith('-')]
    flags = [a for a in sys.argv[2:] if a.startswith('-')]
    if cmd == 'sign':
        sign(args[0], '--skip-if-same' in flags)
    elif cmd == 'verify':
        verify(args[0])
    elif cmd == 'bundle':
        bundle(args[0])
    elif cmd == 'check-bundle':
        check_bundle(args[0] if args else 'license.json')
    elif cmd == 'init-key':
        init_key()
    elif cmd == 'set-pw':
        set_pw(args[0] if args else '')
    elif cmd == 'seal':
        seal(args[0] if args else 'features.json', args[1] if len(args) > 1 else 'license.json')
    elif cmd == 'open':
        open_license(args[0] if args else 'license.json')
    elif cmd == 'check':
        check(args[0] if args else 'license.json')
    elif cmd == 'verify-pw':
        if not args:
            raise SystemExit('用法: python sign_config.py verify-pw 密码 [license.json]')
        verify_pw(args[0], args[1] if len(args) > 1 else 'license.json')
    else:
        raise SystemExit('未知命令: %s\n%s' % (cmd, __doc__))
