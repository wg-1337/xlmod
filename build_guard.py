# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

# -*- coding: utf-8 -*-
"""构建守卫 dex 并取出运行期密钥。

步骤：
  1) 编译 guard-src（纯 Java，无 Android 依赖）
  2) 运行 KeyProbe → 真实密钥（0x5A ^ Guard8.a() ^ Guard9.b()）→ 写 guard-key.txt
  3) 分别用 R8 把 Guard8 / Guard9 打成两个独立 dex：
        guard-dex/classes8.dex（Guard8）
        guard-dex/classes9.dex（Guard9）
     （两者只通过反射互相引用，因此可以分开编译、分开成 dex）
"""
import io, os, subprocess, sys, shutil

JAVA = r"C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\java.exe"
JAVAC = r"C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\javac.exe"
SDK = r"F:\Android\androidsdk\platforms\android-30\android.jar"
R8 = "r8-8.3.37.jar"

def run(cmd, **kw):
    p = subprocess.run(cmd, capture_output=True, **kw)
    if p.returncode != 0:
        sys.stderr.write(p.stdout.decode('utf-8', 'replace'))
        sys.stderr.write(p.stderr.decode('utf-8', 'replace'))
        raise SystemExit('命令失败(%d): %s' % (p.returncode, ' '.join(cmd)))
    return p

# 1) 编译守卫
shutil.rmtree('guard-classes', ignore_errors=True)
os.makedirs('guard-classes', exist_ok=True)
run([JAVAC, '-proc:none', '-encoding', 'UTF-8', '-d', 'guard-classes',
     'guard-src/net/xuele/xuelets/mod/guard/Guard8.java',
     'guard-src/net/xuele/xuelets/mod/guard/Guard9.java',
     'guard-src/probe/KeyProbe.java'])
print('guard javac OK')

# 2) 运行探针取真实密钥
out = run([JAVA, '-cp', 'guard-classes', 'KeyProbe']).stdout.decode().strip()
key = int(out) & 0xFF
io.open('guard-key.txt', 'w').write('0x%02X\n' % key)
print('运行时密钥 key=0x%02X（已写入 guard-key.txt）' % key)

# 3) 分别打两个 dex
shutil.rmtree('guard-dex', ignore_errors=True)
os.makedirs('guard-dex', exist_ok=True)
for name, cls in (('Guard8', 'net.xuele.xuelets.mod.guard.Guard8'),
                  ('Guard9', 'net.xuele.xuelets.mod.guard.Guard9')):
    outdir = 'guard-dex-build-%s' % name
    shutil.rmtree(outdir, ignore_errors=True)
    os.makedirs(outdir, exist_ok=True)   # R8 要求输出目录已存在
    run([JAVA, '-Xmx1g', '-cp', R8, 'com.android.tools.r8.R8', '--release', '--min-api', '19',
         '--lib', SDK, '--pg-conf', 'guard-rules.pro',
         '--output', outdir,
         'guard-classes/net/xuele/xuelets/mod/guard/%s.class' % name])
    produced = os.path.join(outdir, 'classes.dex')
    if not os.path.exists(produced):
        raise SystemExit('R8 未产出 %s 的 dex' % name)
    idx = '8' if name == 'Guard8' else '9'
    shutil.copyfile(produced, 'guard-dex/classes%s.dex' % idx)
    print('  classes%s.dex ← %s (%d 字节)' % (idx, name, os.path.getsize('guard-dex/classes%s.dex' % idx)))
print('守卫 dex 构建完成')
