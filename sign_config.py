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

PUB = 'keys/xlmod_config_ec_public.pem'
PRIV = 'keys/xlmod_config_ec_private.pem'


def sign(path):
    sig_path = path + '.sig'
    subprocess.run(['openssl', 'dgst', '-sha256', '-sign', PRIV, '-out', sig_path + '.bin', path], check=True)
    with open(sig_path + '.bin', 'rb') as f:
        raw = f.read()
    os.remove(sig_path + '.bin')
    with open(sig_path, 'w') as f:
        f.write(base64.b64encode(raw).decode())
    print('已签名: %s (%d 字节签名)' % (sig_path, len(raw)))
    print('配置 sha256: %s' % hashlib.sha256(open(path, 'rb').read()).hexdigest())


def verify(path):
    sig_path = path + '.sig'
    if not os.path.exists(sig_path):
        raise SystemExit('缺少 %s' % sig_path)
    raw = base64.b64decode(open(sig_path).read().strip())
    with open(sig_path + '.bin', 'wb') as f:
        f.write(raw)
    r = subprocess.run(['openssl', 'dgst', '-sha256', '-verify', PUB, '-signature', sig_path + '.bin', path],
                       capture_output=True)
    os.remove(sig_path + '.bin')
    ok = (r.returncode == 0)
    print(('验签通过: ' if ok else '验签失败: ') + path)
    if not ok:
        sys.stdout.write(r.stdout.decode('utf-8', 'replace') + r.stderr.decode('utf-8', 'replace'))
        sys.exit(1)


def bundle(path):
    """把 配置 + 签名 打成一个 license.json（单文件 → CDN 不会出现"新旧不同步"）。
       结构：{"payload":"<base64(配置原始字节)>","sig":"<base64(签名)>"}
    """
    import json as _json
    raw = open(path, 'rb').read()
    sig_path = path + '.sig'
    if not os.path.exists(sig_path):
        raise SystemExit('缺少 %s（先 sign）' % sig_path)
    sig = open(sig_path).read().strip()
    out = _json.dumps({'payload': base64.b64encode(raw).decode(), 'sig': sig}, ensure_ascii=False, indent=2)
    open('license.json', 'w', encoding='utf-8').write(out)
    print('已生成 license.json（%d 字节，payload=%d 字节）' % (len(out), len(raw)))


def check_bundle(path='license.json'):
    """校验 license.json：签名必须能验证 payload"""
    import json as _json
    o = _json.load(open(path, encoding='utf-8'))
    raw = base64.b64decode(o['payload'])
    sig = base64.b64decode(o['sig'])
    open('_bz.bin', 'wb').write(raw)
    open('_bz.sig', 'wb').write(sig)
    r = subprocess.run(['openssl', 'dgst', '-sha256', '-verify', PUB, '-signature', '_bz.sig', '_bz.bin'],
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
    if cmd == 'bundle':
        bundle(sys.argv[2])
    elif cmd == 'check-bundle':
        check_bundle(sys.argv[2] if len(sys.argv) > 2 else 'license.json')
    else:
        {'sign': sign, 'verify': verify}[cmd](sys.argv[2])
