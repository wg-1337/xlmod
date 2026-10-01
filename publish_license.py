# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

"""一键发布授权（V4.3p）：bundle（配置+签名）→ 删仓库明文 → 提交推送 → 远程复验。

    python publish_license.py            # 完整流程
    python publish_license.py --nopush   # 只本地签发/校验，不提交推送
    python publish_license.py --edit     # 先打开 features.json 编辑

工作方式（V4.3p）：
  · 配置一直是**明文**（features.json，只在作者本地/工作区，方便你直接改）；
  · 管理员密码也写在 features.json 里（admin_password），签发时换算成 PBKDF2 校验块；
  · 仓库里只提交 `license.json`（payload = base64(配置) + 作者私钥签名）——**没有明文文件、没有密码明文**。
"""
import io
import json
import os
import subprocess
import sys

import sign_config as sc

REPO = 'tmp-repo' if os.path.isdir('tmp-repo') else '.'
RAW_URL = 'https://raw.githubusercontent.com/wg-1337/xlmod/main/license.json'
PLAIN_LEGACY = ['features.json', 'features.json.sig', 'features.json.sha256']


def sh(args, cwd=None, check=False):
    print('    $ ' + ' '.join(args))
    r = subprocess.run(args, cwd=cwd, capture_output=True)
    out = (r.stdout or b'').decode('utf-8', 'replace') + (r.stderr or b'').decode('utf-8', 'replace')
    if out.strip():
        print('      ' + out.strip().replace('\n', '\n      '))
    if check and r.returncode != 0:
        raise SystemExit('命令失败: %s' % ' '.join(args))
    return r.returncode


def find_cfg():
    """明文配置来源：工作区根 -> 仓库内 -> 上级目录 -> xlmod-config 副本"""
    for p in ('features.json', os.path.join('..', 'features.json'),
              os.path.join(REPO, 'features.json'),
              os.path.join('xlmod-config', 'features.json')):
        if os.path.exists(p):
            return p
    raise SystemExit('找不到 features.json（明文配置只在作者本地/工作区，不提交仓库）')


def main():
    args = sys.argv[1:]
    nopush = '--nopush' in args
    edit = '--edit' in args

    print('=' * 62)
    print(' XLMod 授权发布 V4.3p（明文配置在你本地，仓库只收签名后的 license.json）')
    print('  仓库目录 : %s' % os.path.abspath(REPO))
    print('  是否推送 : %s' % ('否' if nopush else '是'))
    print('=' * 62)

    if not os.path.exists(sc.PRIV):
        raise SystemExit('找不到签名私钥：%s（私钥不进仓库）' % sc.PRIV)

    cfg = find_cfg()
    if edit:
        print('[1/5] 打开记事本编辑 %s ...' % cfg)
        subprocess.run(['notepad', cfg])
    else:
        print('[1/5] 使用配置 %s（要改内容：python publish_license.py --edit）' % cfg)

    print('[2/5] 签发（配置 + 管理员校验块）-> %s/license.json' % REPO)
    out = os.path.join(REPO, 'license.json')
    sc.bundle(cfg, out)

    print('[3/5] 清理仓库里的明文配置文件（V4.3p 起不再上传明文）')
    for name in PLAIN_LEGACY:
        p = os.path.join(REPO, name)
        if os.path.exists(p):
            os.remove(p)
            print('      已删除 %s' % p)
    if os.path.isdir('xlmod-config'):
        io.open(os.path.join('xlmod-config', 'license.json'), 'w', encoding='utf-8', newline='\n').write(
            io.open(out, encoding='utf-8').read())
        print('      已同步本地副本 xlmod-config/license.json')

    print('[4/5] 本地自检（验签 + 摘要）')
    sc.check(out)

    if nopush:
        print('[4/5] 跳过推送（--nopush）')
    else:
        print('[4/5] 提交并推送')
        sh(['git', 'add', '-A'], cwd=REPO, check=True)
        if sh(['git', 'diff', '--cached', '--quiet'], cwd=REPO) != 0:
            sh(['git', 'commit', '-m', '授权配置更新（V4.3p：明文配置仅本地，仓库只收签名后的 license.json）'],
               cwd=REPO, check=True)
        else:
            print('      没有新改动需要提交')
        if sh(['git', 'push', 'origin', 'main'], cwd=REPO) != 0:
            print('      直连/代理失败，改为绕过本地代理重试 ...')
            sh(['git', '-c', 'http.proxy=', '-c', 'https.proxy=', 'push', 'origin', 'main'], cwd=REPO)
        else:
            print('      推送成功')

    print('[5/5] 远程复验')
    tmp = os.path.join(REPO, '_remote_license.json')
    ok = False
    for cmd in (['curl', '-sS', '-m', '60', '-o', tmp, RAW_URL],
                ['curl', '-sS', '-m', '60', '--noproxy', '*', '-o', tmp, RAW_URL]):
        if sh(cmd) == 0 and os.path.exists(tmp) and os.path.getsize(tmp) > 0:
            ok = True
            break
    if not ok:
        print('      [警告] 远端下载失败，稍后可重跑本脚本复验')
    else:
        try:
            sc.check(tmp, quiet=True)
            same = (json.load(io.open(out, encoding='utf-8')).get('payload')
                    == json.load(io.open(tmp, encoding='utf-8')).get('payload'))
            print('      %s' % ('=> 一致：远端已是最新授权（端上最长 60 秒内生效）' if same
                                else '=> 不一致：远端还是旧版本（CDN 缓存一般 5 分钟内刷新）'))
        except SystemExit:
            print('      [警告] 远端 license.json 校验失败（可能是 CDN 缓存未刷新）')
        os.remove(tmp)

    print('=' * 62)
    print(' 端上地址 %s' % RAW_URL)
    print(' 仓库页面 https://github.com/wg-1337/xlmod')
    print('=' * 62)


if __name__ == '__main__':
    main()
