# XLMod 远程授权配置（仓库：https://github.com/wg-1337/xlmod）

## 0. V4.3p 起：仓库里只有密文

端上只读仓库根目录的 `license.json`：

```json
{
  "v": 2,
  "alg": "AES-256-CBC/PBKDF2-HMAC-SHA256/ECDSA-P256",
  "salt": "<base64 16 字节>",
  "enc": "<base64: iv(16) ‖ AES-256-CBC 密文>",
  "sig": "<base64: ECDSA P-256 签名>"
}
```

* **明文**（原 `features.json` 的内容 + `admin` 管理员密码校验块）用 AES-256-CBC 加密后放在 `enc`；
* **密钥**由本地主密钥派生：`key = HMAC-SHA256(主密钥, salt + "XLModLic-v2")`；
  主密钥在 `keys/license_key.txt`（**不提交**），端上对应常量在 `mod-src/.../XLModSecrets.java`
  —— 仓库里放的是**占位值**，所以**拿到 license.json 也解不开**；
* **签名**覆盖 `XLModLic-v2|<salt>|<enc>` 的 UTF-8 字节（作者私钥 ECDSA P-256），端上只内置公钥；
* 旧版明文格式 `{"payload":…,"sig":…}` 端上仍兼容，但仓库不再分发（`.gitignore` 已排除 `features.json*`）。

## 1. 语义（重要：这是"限制/授权"手段）

| 情况 | 结果 |
|---|---|
| **从来没成功拉到过授权** | **锁死**：除 `privacy`（隐私隐藏）和 `logs`（日志，留作报障）外，全部功能区不可用；**不读取任何本地授权文件**（没有离线兜底） |
| 拉到并验签/解密成功 | 按 `features` 表逐项开/关；未列出的项按 `default`（缺省 `false` = 关） |
| `"kill": true` | 熔断：全部功能停用（含隐藏类） |
| **管理员已解锁**（面板输入正确密码） | **全部功能放行**：优先级高于 features / kill / 有效期（作者自己的后门） |
| App 在前台 | **每 60 秒**自动拉一次；内容有变化 → **立即生效**并重建面板 |
| 每次打开面板 | 弹一次 `notice` 公告 |

## 2. 明文结构（加密前；作者本地维护 `features.json`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `version` | int | 版本号，仅记录 |
| `default` | bool | `features` 未列出的功能区默认值；**缺省 false（锁）** |
| `kill` | bool | 远程熔断，`true` = 全停 |
| `notice` | string | 公告，每次打开 Mod 面板弹出 |
| `expires` | int | 有效期（epoch 秒，0/缺省 = 长期） |
| `features` | object | 功能区 → 是否开启 |
| `admin` | object | **管理员密码校验块**（由 `seal` 自动写入，不要手写）：<br>`{"alg":"PBKDF2-HMAC-SHA256","iters":20000,"salt":"…","hash":"…"}` |

## 3. 功能区 ID 对照

| ID | 面板分组 |
|---|---|
| `identity` | 教师身份 |
| `teacher_tools` | 教师工具（新增学生等） |
| `cloud_keep` | 视频上传压缩 / 云端保持原片 |
| `notify_recall` | 通知管理（撤回并删除常态化） |
| `homework` | 布置作业（修复） |
| `auto_sign` | 自动签到 |
| `auto_challenge` | 自动打榜（可选学科） |
| `cloud_flower` | 云朵助手 |
| `rank` | 排行榜 |
| `answer` | 金榜题名 / 答题助手 / 题库 |
| `privacy` | 隐私隐藏（**锁定状态下仍可用**） |
| `logs` | 日志（**锁定状态下仍可用**，用于报障） |

## 4. 常用操作

**只给某个用户开「布置作业」和「云原片」**（其余全关，明文配置示例）：
```json
{
  "version": 3,
  "default": false,
  "kill": false,
  "notice": "你的授权已开通：布置作业 + 云端保持原片",
  "features": { "homework": true, "cloud_keep": true, "privacy": true, "logs": true }
}
```

**临时全停（例如发现严重问题）**：
```json
{ "version": 4, "kill": true, "notice": "服务端维护中，请稍后再试。" }
```
改完都要重新 `seal` 才会生效（密文每次不同，属正常）。

## 5. 管理员密码与主密钥

```bash
python sign_config.py init-key             # 生成主密钥（写 keys/license_key.txt + 端上常量）
python sign_config.py set-pw 你的密码       # 设置/更换管理员密码（写 keys/admin_pw.txt）
python sign_config.py seal features.json   # 加密 + 签名 → license.json
python sign_config.py check license.json   # 验签 + 解密 + 打印摘要
python sign_config.py verify-pw 你的密码    # 像端上一样校验管理员密码
python sign_config.py open license.json    # 直接看明文（本机有主密钥时）
```

* 换主密钥 → 必须重新 `seal` 并推送，否则端上解不开（保持锁定）；
* 换管理员密码 → 同样要重新 `seal`（校验块在密文里）；
* 主密钥、管理员密码、私钥**都不要提交**（`.gitignore` 已覆盖）。

## 6. 注意

1. 授权**不落盘**：端上只在内存里保存当前配置，重启 App 后必须重新拉到才解锁（刻意的限制语义）；
2. 断网 / 仓库 404 / 密文被改（验签失败）→ 保持锁定；原因会显示在面板「授权状态（远程配置）」并写日志 `[功能开关]`；
3. 修改后最长 60 秒生效，也可在面板点「立即校验授权」；
4. **老版本客户端**（V4.2q 及以前）只认明文 `payload` 格式 → 换了加密授权后它们会保持锁定，
   需要在面板/弹窗里升级到 V4.3p（新版有强制更新检测）。

## 7. Windows 一键更新脚本

仓库根目录 `update_license.bat`（V2.0，走加密授权）：

```bat
update_license.bat            :: 加密 -> 签名 -> 自检 -> 删除仓库明文 -> 推送 -> 远程复验
update_license.bat edit       :: 先编辑 features.json 再执行
update_license.bat nopush     :: 只本地加密/校验
```

脚本只负责找 `python`/`openssl`，真正的流程在 `publish_license.py` 里：

1. `sign_config.py seal` 加密 + 签名（私钥 `keys/xlmod_config_ec_private.pem`）；
2. 删除仓库里的 `features.json` / `features.json.sig` / `features.json.sha256`（不再上传明文）；
3. 本地自检（验签 + 解密 + 打印摘要）；
4. `git add/commit/push`（推送失败会绕过本地代理重试一次）；
5. 下载远端 `license.json` 复验并与本地密文比对，明确告诉你"远端是否已生效"。
