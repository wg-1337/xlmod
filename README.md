# XLMod

学乐云（`net.xuele.xuelets`）安卓客户端的**功能增强模块**。本仓库只包含**我们自己新写的代码**；
对宿主 App 的修改（反编译后的 smali 注入）因版权原因**不在此仓库**，仅提供注入点清单（见 `docs/INJECTION_POINTS.md`）。

> 许可证：**AGPL-3.0**（见 `LICENSE`）。使用、修改、再分发请遵守 AGPL 条款；
> 通过网络提供服务时，必须向使用者提供对应源码。

---

## 1. 功能

| 功能区 ID | 功能 |
|---|---|
| `identity` | 教师身份伪装（身份等级可调：教师 / 班主任 / 校级管理 / 教研 / 教育局） |
| `teacher_tools` | 教师工具（以指定 userId 编辑学生等） |
| `cloud_keep` | **云端保持原片**：本地不重编码 + 容器宽高伪装 + 扩展名 `bin`，绕过服务端转码 |
| `notify_recall` | 通知撤回常态化（「撤回并删除」与「删除」始终同时可用） |
| `homework` | **布置作业修复**：抓取班级/学生/课本/课时 → 注入原版发作业页；发布时自动补课时与拉题 |
| `auto_sign` | 自动签到（含无尽大陆） |
| `auto_challenge` | 自动打榜（金榜题名·同学对战，自动答题与换科；**打过的学科会记录，可勾选「每次要打的学科」**） |
| `cloud_flower` | 云朵助手（自动领取） |
| `rank` | 排行榜增强 |
| `answer` | 答题助手（**题库**：同学对战题目自动入库 → 普通挑战按正确答案匹配作答；本地/判题接口/详情接口 + 悬浮窗） |
| `privacy` | 隐私隐藏（deviceId 伪装） |
| `logs` | 日志与崩溃记录（便于报障） |

> V4.3p 另有两项**本地功能**（不受云端开关影响）：**管理员解锁**（面板输入管理员密码 → 放行全部功能）
> 与**题库**（可由面板单独关闭）。

## 2. 远程授权与公告（重要）

功能**由仓库根目录的加密授权控制**（V4.3p 起仓库里只有密文）：

```
license.json       单文件加密授权：{"v":2,"salt":…,"enc":…,"sig":…}
                   enc = AES-256-CBC(salt‖iv‖明文)；明文 = 功能开关 + 公告 + 有效期 + 管理员密码校验块
```

App 只读这一个文件（V4.2 及以前的明文 `features.json` + `.sig` 仍兼容，但仓库不再放明文）。

* **端上只内置公钥**（验签）与**本地主密钥派生**（解密）；验签不过 / 下载失败 / 解不开 / 已过期 → **锁死**
  （除 `privacy`、`logs` 外全部禁用，不读本地缓存）；
* **地址写死在代码里**（`XLModFeatures.DEFAULT_URL`），面板不提供修改入口 → 用户无法自建配置自解锁；
* 解密后按 `features` 逐项开关，未列出的按 `default`（缺省 `false`）；`"kill": true` 全停；
* `notice` = **公告**，每次打开 Mod 面板弹出；`expires` = 有效期（epoch 秒，`0`/缺省表示不过期）；
* App 在前台**每 60 秒**校验一次，配置有变化**立即生效**。

### 2.1 管理员密码（V4.3p）

* 管理员密码**不在代码里**：签发时只把它的 **PBKDF2-HMAC-SHA256 校验块**（盐/迭代数/哈希）写进授权明文，
  再随整份授权一起 AES 加密 —— 所以仓库里既看不到功能开关，也看不到密码；
* 面板「管理员解锁」输入密码 → 校验通过即在本机放行**全部功能**（优先级高于 features / kill / 有效期），
  可随时「退出管理员模式」回到按授权开关；
* 校验块在第一次成功校验授权后缓存到本机 → 之后断网也能解锁；
* 公开仓库里放的是**占位主密钥**（`mod-src/.../XLModSecrets.java`），因此**拿到 license.json 也解不开**。

### 2.2 签发与发布（作者本地）

```bash
python sign_config.py init-key            # ① 生成主密钥（写 keys/license_key.txt + 端上常量）
python sign_config.py set-pw 你的密码      # ② 设置管理员密码（写 keys/admin_pw.txt，不提交）
python sign_config.py seal features.json  # ③ 加密+签名 → license.json（仓库只提交它）
python sign_config.py check  license.json # ④ 验签 + 解密 + 打印摘要
python sign_config.py verify-pw 你的密码   # ⑤ 像端上一样校验管理员密码
```

`features.json`（明文配置）只留在作者本地/工作区，`.gitignore` 已排除；仓库里只有 `license.json`。

### 2.3 Windows 一键更新（推荐）

仓库根目录 `update_license.bat`（双击即可，V2.0 起走加密授权）：

```
update_license.bat              加密 → 签名 → 自检 → 删除仓库明文 → 提交推送 → 远程复验
update_license.bat edit         先编辑 features.json，再走上面的流程
update_license.bat nopush       只做本地加密/校验
```

配套小工具：`sign_config.py`（init-key / set-pw / seal / check / open / verify-pw + 旧版 sign/verify/bundle）、
`publish_license.py`（一键发布）、`show_license.py`（打印授权摘要）、`compare_license.py`（本地 vs 远端一致性）。

## 3. 开始开发前请先读

* **[`DEVELOPMENT_GUIDE.md`](DEVELOPMENT_GUIDE.md)** —— 开发引导：文档地图 + "改哪个功能要动哪些文件"对照表 + 硬约束/坑 + 标准工作流 + 交付要求。

## 3. 代码结构

```
mod-src/net/xuele/xuelets/mod/      Mod 全部新增 Java 代码
  ├── XLModActivity.java            面板 UI（Miuix 风格，无 Compose）
  ├── XLModConfig.java              配置读写 + 本地数据存储（JSON 双写 + 备份）
  ├── XLModHelper.java              业务实现（云原片伪装 / 作业抓取注入 / 身份 / 签到打榜 / 答题）
  ├── XLModFeatures.java            远程授权开关（60 秒刷新、公告、实时生效、失败即锁）
  └── Obf.java                      字符串解密 + 多 dex 密钥链
guard-src/                          多 dex 互锁守卫（由 guard_gen.py 生成）
guard_gen.py                        守卫代码生成器（固定种子，可复现）
build_guard.py                      编译守卫 → 实跑取运行期密钥 → 打成 classes8/classes9
obf_strings.py                      字符串加密（密钥取自 guard-key.txt）
inject_dexes.py                     把 classes7/8/9 一起写回 APK（校验索引连续）
guard-rules.pro / obf-rules.pro     混淆 keep 规则（改错会导致功能静默失效）
verify_dex_interlock.py             多 dex 互锁验证（5 组断言）
verify_remote_lock.py               远程授权语义验证（结构 + 行为）
docs/                               设计与逆向记录
```

## 4. 构建（只构建本仓库的开源部分）

需要一个**已注入的 APK 骨架**（不随仓库分发）与 Android SDK：

```bash
python guard_gen.py          # ① 生成守卫类（固定种子）
python build_guard.py        # ② 编译守卫 → guard-key.txt（运行期密钥）→ classes8/9 dex
python obf_strings.py        # ③ 字符串加密（密钥来自 guard-key.txt）
javac -proc:none -source 1.8 -target 1.8 -encoding UTF-8 \
      -bootclasspath <android.jar> -classpath <你的 stub 类> \
      -d obf-classes obf-src/net/xuele/xuelets/mod/*.java
java -cp r8.jar com.android.tools.r8.R8 --release --min-api 19 \
      --lib <android.jar> --lib <你的 stub 类> --pg-conf obf-rules.pro \
      --output obf-dex <obf-classes 下的 class 列表>
python inject_dexes.py       # ④ classes7/8/9 写回骨架 APK
zipalign -f 4 … && apksigner sign …        # ⑤ 对齐 + 签名
python verify_dex_interlock.py             # ⑥ 验证互锁
python verify_remote_lock.py               # ⑦ 验证授权语义
```

> `-proc:none` 是必须的（部分环境存在损坏的注解处理器，会导致 javac 报
> "服务配置文件不正确"）。

## 5. 多 dex 互锁（防删改）

* `classes7.dex` = Mod 主逻辑；`classes8.dex` = `Guard8`；`classes9.dex` = `Guard9`；
* 字符串密钥 = `0x5A ^ Guard8.a() ^ Guard9.b()`，两个守卫**互相反射校验**；
* 因此**删掉任意一个 dex**、或手工改守卫常量，都会让密钥算错 → Mod 内字符串全部乱码（功能整体失效）；
* 密钥由构建期**实跑守卫代码**取得，源码与产物强绑定。

细节见 [`docs/MULTIDEX_INTERLOCK.md`](docs/MULTIDEX_INTERLOCK.md)。

## 6. 云端保持原片（核心机制）

**配方**：① 本地不重编码；② 只把容器 `tkhd` / `avc1·hvc1` 宽高写成 1280×720；③ 上传扩展名 `bin`。

**判定是否成功**：看云端的下载链接 —— `files/<md5>.mp4` = 原样保存（成功）；
`files/mp4_<md5>.mp4` = 被服务端转码（失败）。

细节、注入点全表、不变量与排障手册见
[`docs/CLOUD_KEEP_ORIGINAL_DESIGN.md`](docs/CLOUD_KEEP_ORIGINAL_DESIGN.md)。

## 7. 边界与免责

* 本仓库**不包含**宿主 App 的任何原始代码、资源、反编译产物（见 `docs/OPEN_SOURCE_SCOPE.md`）；
* 仅用于学习与研究；使用者需自行承担合规与账号风险；
* AGPL-3.0 覆盖本仓库全部内容。
