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


if __name__ == '__main__':
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    {'sign': sign, 'verify': verify}[sys.argv[1]](sys.argv[2])
