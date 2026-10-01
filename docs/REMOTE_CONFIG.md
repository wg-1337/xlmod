# XLMod 远程授权配置（仓库：https://github.com/wg-1337/xlmod）

## 0. 授权文件长什么样（V4.3p）

仓库根目录只有 **一个** 授权文件：

```json
{ "payload": "<base64(明文配置原始字节)>", "sig": "<base64(ECDSA P-256 签名)>" }
```

* **明文配置**（`features.json`）**只在作者本地**，用记事本直接改；`bundle` 时把
  `admin_password` 换算成 `admin` 校验块，再把整份配置签名打包进 `license.json`；
* 端上只内置**公钥**验签（签名覆盖 payload 原始字节），验签不过 → 保持锁定；
* 上传的 payload 里 **没有管理员密码明文**，只有 `admin`（PBKDF2 校验块）；
* 想让别的用户自建授权：改自己的明文配置 + 用自己的私钥签发即可（公钥在端上，换不了）。

## 1. 语义（重要：这是"限制/授权"手段）

| 情况 | 结果 |
|---|---|
| **从来没成功拉到过配置** | **锁死**：除 `privacy`（隐私隐藏）和 `logs`（日志，留作报障）外，全部功能区不可用；**不读取任何本地配置文件**（没有离线兜底） |
| 拉到配置 | 按 `features` 表逐项开/关；未列出的项按 `default`（缺省 `false` = 关） |
| `"kill": true` | 熔断：全部功能停用（`privacy` 例外：它属于用户自身权益，代码里 `ALWAYS_ON` 优先） |
| **管理员已解锁**（面板输入正确密码） | **全部功能放行**：优先级高于 features / kill / 有效期（作者自己的后门） |
| App 在前台 | **每 60 秒**自动拉一次；内容有变化 → **立即生效**并重建面板 |
| 每次打开面板 | 弹一次 `notice` 公告 |

## 2. 明文配置字段（本地 features.json）

| 字段 | 类型 | 说明 |
|---|---|---|
| `version` | int | 版本号，仅记录 |
| `default` | bool | `features` 未列出的功能区默认值；**缺省 false（锁）** |
| `kill` | bool | 远程熔断，`true` = 全停 |
| `notice` | string | 公告，每次打开 Mod 面板弹出 |
| `expires` | int | 有效期（epoch 秒，0/缺省 = 长期） |
| `features` | object | 功能区 → 是否开启 |
| `admin_password` | string | **管理员密码（明文，只在本地）**；签发时被换算成下面的 `admin` 块，不上传 |
| `admin` | object | 签发产物：`{"alg":"PBKDF2-HMAC-SHA256","iters":20000,"salt":…,"hash":…}`（**没有密码明文**） |

> 签发脚本会剥掉 `admin_password` 再签名，所以仓库里的 payload 只有 `admin` 校验块。
> 端上也兼容"payload 里直接写 `admin_password` 明文"的写法（面板按明文比对），但不推荐。

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

## 4. 常用操作（改本地 features.json 后重新签发）

**只给某个用户开「布置作业」和「云原片」**（其余全关）：
```json
{
  "version": 3,
  "default": false,
  "kill": false,
  "notice": "你的授权已开通：布置作业 + 云端保持原片",
  "admin_password": "你的管理员密码",
  "features": { "homework": true, "cloud_keep": true, "privacy": true, "logs": true }
}
```

**临时全停（例如发现严重问题）**：
```json
{ "version": 4, "kill": true, "notice": "服务端维护中，请稍后再试。" }
```

## 5. 命令速查

```bash
python sign_config.py set-pw 新密码          # 改密码（写进本地 features.json）
python sign_config.py bundle features.json  # 签发 → license.json（含 admin 校验块）
python sign_config.py check  license.json   # 验签 + 摘要
python sign_config.py verify-pw 密码         # 像端上一样校验密码
python sign_config.py show-pw license.json  # 看 admin 块信息（不显示密码）
python publish_license.py                   # 一键发布（删仓库明文 → 推送 → 远程复验）
```

* 换密码 / 改开关 → 重新 `bundle`（或跑 `publish_license.py`）；
* 私钥 `keys/xlmod_config_ec_private.pem` 与明文 `features.json` **都不要提交**（`.gitignore` 已覆盖）。

## 6. 注意

1. 授权**不落盘**：端上只在内存里保存当前配置（含 admin 校验块），重启 App 后必须重新拉到才解锁
   （解锁标记 `admin_unlocked` 是本地开关，会保留）；
2. 断网 / 仓库 404 / payload 被改（验签失败）→ 保持锁定；原因会显示在面板「授权状态（远程配置）」并写日志 `[功能开关]`；
3. 修改后最长 60 秒生效，也可在面板点「立即校验授权」；
4. payload 是 base64 的明文配置 —— 想彻底隐藏配置内容，请自行在本地做额外处理（本项目保持简单方案）。

## 7. Windows 一键更新脚本

仓库根目录 `update_license.bat`（V2.1）：

```bat
update_license.bat            :: 签发 -> 自检 -> 删除仓库明文 -> 推送 -> 远程复验
update_license.bat edit       :: 先编辑 features.json 再执行
update_license.bat nopush     :: 只本地签发/校验
```

脚本只负责找 `python`/`openssl`，真正的流程在 `publish_license.py` 里：
签发（配置 + admin 校验块 + 签名）→ 删除仓库里的明文配置 → 本地验签 → `git add/commit/push`
→ 下载远端 `license.json` 复验并与本地 payload 比对。
