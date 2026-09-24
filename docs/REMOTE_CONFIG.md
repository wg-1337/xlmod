# XLMod 远程授权配置（仓库：https://github.com/wg-1337/xlmod）

把本目录的 `features.json` 放在仓库**默认分支**根目录即可，端上地址是：

```
https://raw.githubusercontent.com/wg-1337/xlmod/main/features.json
```

> 若默认分支是 `master`，请把地址里的 `main` 换成 `master`（面板「配置地址」里也能改）。

## 1. 语义（重要：这是"限制/授权"手段）

| 情况 | 结果 |
|---|---|
| **从来没成功拉到过配置** | **锁死**：除 `privacy`（隐私隐藏）和 `logs`（日志，留作报障）外，全部功能区不可用；**不读取任何本地配置文件**（没有离线兜底） |
| 拉到配置 | 按 `features` 表逐项开/关；未列出的项按 `default`（缺省 `false` = 关） |
| `"kill": true` | 熔断：全部功能停用（含隐藏类） |
| App 在前台 | **每 60 秒**自动拉一次；内容有变化 → **立即生效**并重建面板 |
| 每次打开面板 | 弹一次 `notice` 公告 |

## 2. 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `version` | int | 版本号，仅记录 |
| `default` | bool | `features` 未列出的功能区默认值；**缺省 false（锁）** |
| `kill` | bool | 远程熔断，`true` = 全停 |
| `notice` | string | 公告，每次打开 Mod 面板弹出 |
| `features` | object | 功能区 → 是否开启 |

## 3. 功能区 ID 对照

| ID | 面板分组 |
|---|---|
| `identity` | 教师身份 |
| `teacher_tools` | 教师工具（新增学生等） |
| `cloud_keep` | 视频上传压缩 / 云端保持原片 |
| `notify_recall` | 通知管理（撤回并删除常态化） |
| `homework` | 布置作业（修复） |
| `auto_sign` | 自动签到 |
| `auto_challenge` | 自动打榜 |
| `cloud_flower` | 云朵助手 |
| `rank` | 排行榜 |
| `answer` | 金榜题名 / 答题助手 |
| `privacy` | 隐私隐藏（**锁定状态下仍可用**） |
| `logs` | 日志（**锁定状态下仍可用**，用于报障） |

## 4. 常用操作

**只给某个用户开「布置作业」和「云原片」**（其余全关）：
```json
{
  "version": 2,
  "default": false,
  "kill": false,
  "notice": "你的授权已开通：布置作业 + 云端保持原片",
  "features": { "homework": true, "cloud_keep": true, "privacy": true, "logs": true }
}
```

**临时全停（例如发现严重问题）**：
```json
{ "version": 3, "kill": true, "notice": "服务端维护中，请稍后再试。" }
```

**公告改一次就够**（内容变化才会触发面板实时重建；公告本身每次打开面板都会弹）。

## 5. 注意

1. 配置**不落盘**：端上只在内存里保存当前配置，重启 App 后必须重新拉到才会解锁（这是刻意的限制语义）；
2. 断网 / 仓库 404 / JSON 写错 → 保持锁定；错误原因与地址会显示在面板「授权状态（远程配置）」里，也会写日志 `[功能开关]`；
3. 修改配置后最长 60 秒生效，也可以在面板点「立即刷新配置」立刻生效。
