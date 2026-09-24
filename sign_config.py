# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

"""签发 / 校验远程授权配置。

用法（签发，需要私钥 keys/xlmod_config_ec_private.pem）：

    python sign_config.py sign  features.json            # 生成 features.json.sig
    python sign_config.py verify features.json           # 校验签名（用内置公钥）

原理：对 features.json 的**原始字节**做 SHA256withECDSA（P-256），
签名结果 Base64 单行写入同名 .sig 文件。端上只内置公钥，验签不过 → 保持锁定。

私钥**绝不要**提交到仓库（.gitignore 已排除 keys/）。
"""
import base64
import hashlib
import subprocess
import sys
import os

# openssl 可执行文件：优先环境变量 XLMod_OPENSSL（Windows 下 cmd 的 PATH 里通常没有 openssl，
# 但 Git for Windows 自带；update_license.bat 会自动探测并设置该变量）
OPENSSL = os.environ.get('XLMod_OPENSSL') or 'openssl'


def _find_key(name):
    """在 keys/、../keys/、%XLMod_KEYS% 里找密钥（仓库里只有公钥，私钥在作者本地）"""
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
    """把 配置 + 签名 打成一个 license.json（单文件，避免 CDN 上"配置/签名两份缓存"不同步）。
       结构：{"payload":"<base64(配置原始字节)>","sig":"<base64(签名)>"}
       注意：以 LF 写出 —— git 入库会把 CRLF 转 LF，写 CRLF 会造成"每次运行都像有改动"的噪音。
    """
    import json as _json
    raw = open(path, 'rb').read()
    sig_path = path + '.sig'
    if not os.path.exists(sig_path):
        raise SystemExit('缺少 %s（先 sign）' % sig_path)
    sig = open(sig_path).read().strip()
    out = _json.dumps({'payload': base64.b64encode(raw).decode(), 'sig': sig},
                      ensure_ascii=False, indent=2)
    with open('license.json', 'w', encoding='utf-8', newline='\n') as f:
        f.write(out)
    print('已生成 license.json（%d 字节，payload=%d 字节）' % (len(out), len(raw)))


def check_bundle(path='license.json'):
    """校验 license.json：签名必须能验证 payload"""
    import json as _json
    o = _json.load(open(path, encoding='utf-8'))
    raw = base64.b64decode(o['payload'])
    sig = base64.b64decode(o['sig'])
    open('_bz.bin', 'wb').write(raw)
    open('_bz.sig', 'wb').write(sig)
    r = subprocess.run([OPENSSL, 'dgst', '-sha256', '-verify', PUB, '-signature', '_bz.sig', '_bz.bin'],
                       capture_output=True)
    os.remove('_bz.bin')
    os.remove('_bz.sig')
    ok = (r.returncode == 0)
    print(('license.json 校验通过' if ok else 'license.json 校验失败'))
    if not ok:
        sys.exit(1)


if __name__ == '__main__':
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    cmd = sys.argv[1]
    args = [a for a in sys.argv[2:] if not a.startswith('-')]
    flags = [a for a in sys.argv[2:] if a.startswith('-')]
    if cmd == 'sign':
        sign(args[0], '--skip-if-same' in flags)
        sys.exit(0)
    if cmd == 'bundle':
        bundle(args[0])
    elif cmd == 'check-bundle':
        check_bundle(args[0] if args else 'license.json')
    else:
        {'sign': sign, 'verify': verify}[cmd](sys.argv[2])
