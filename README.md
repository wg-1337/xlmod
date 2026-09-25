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
| `auto_challenge` | 自动打榜（金榜题名·同学对战，自动答题与换科） |
| `cloud_flower` | 云朵助手（自动领取） |
| `rank` | 排行榜增强 |
| `answer` | 答题助手（本地/判题接口/详情接口三来源 + 悬浮窗） |
| `privacy` | 隐私隐藏（deviceId 伪装） |
| `logs` | 日志与崩溃记录（便于报障） |

## 2. 远程授权与公告（重要）

功能**由仓库根目录的签名配置控制**：

```
license.json       单文件授权（配置 + 签名打包在一起，推荐；避免 CDN 缓存不同步导致误锁）
features.json      授权配置（明文，便于维护/审阅）
features.json.sig  作者私钥签发的 ECDSA P-256 签名（Base64）
```

App 会**优先读取 license.json**（配置与签名同文件 → 只有一份缓存，不会出现新签名配旧配置）；
取不到时回退到 features.json + features.json.sig。

* **端上只内置公钥**：验签不过 / 下载失败 / 已过期 → **锁死**（除 `privacy`、`logs` 外全部禁用，不读本地缓存）；
* **地址写死在代码里**（`XLModFeatures.DEFAULT_URL`），面板不提供修改入口 → 用户无法自建配置自解锁；
* 拉到配置 → 按 `features` 逐项开关，未列出的按 `default`（缺省 `false`）；`"kill": true` 全停；
* `notice` = **公告**，每次打开 Mod 面板弹出；
* `expires` = 有效期（epoch 秒，`0`/缺省表示不过期），过期自动锁定；
* App 在前台**每 60 秒**校验一次，配置有变化**立即生效**。

签发（作者本地，私钥不进仓库）：

```bash
python sign_config.py sign   features.json    # 生成 features.json.sig
python sign_config.py verify features.json    # 校验
```

字段与示例见 [`docs/REMOTE_CONFIG.md`](docs/REMOTE_CONFIG.md)。

### Windows 一键更新（推荐）

仓库根目录提供 `update_license.bat`（双击即可）：

```
update_license.bat              签名 → 打包 → 校验 → 提交推送 → 远程复验
update_license.bat edit         先打开记事本改 features.json，再走上面的流程
update_license.bat nopush       只做本地签名/打包/校验
```

脚本会：
1. 检查 `python / openssl / git`；
2. 用私钥签名 `features.json`（**配置未改动会自动跳过**，避免无意义提交）；
3. 生成单文件 `license.json`（配置 + 签名）并本地校验；
4. `git add/commit/push`（推送失败会自动绕过本地代理重试）；
5. **下载远端 `license.json` 验签，并与本地比对**：一致=已生效；不一致=CDN 缓存未刷新（一般 5 分钟内自愈）。

配套小工具：`sign_config.py`（sign / verify / bundle / check-bundle）、`show_license.py`（打印配置摘要）、
`compare_license.py`（本地 vs 远端一致性）。

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
