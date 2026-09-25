# XLMod 开发引导（AI/人类通用）

> **先读这一页再动手。** 它把仓库里所有文档串成一张地图，并给出"**要改某个功能 → 必须动哪些文件 → 怎么验证**"的对照表。
> 版本：V4.1b ｜ 许可证：AGPL-3.0 ｜ 仓库：https://github.com/wg-1337/xlmod
> **注**：本页为公开版，宿主侧注入点用泛称描述（具体类名/补丁属闭源骨架，不随仓库分发）；
> 本地开发版的同类文档含精确类名与行号，供本机 AI 会话使用。
> 提交身份：`wg-1337`（本机 git 已配置，**不要**用 `-c user.name=...` 覆盖）。

---

## 0. 三分钟了解这个项目

* 目标是学乐云客户端（`net.xuele.xuelets` 5.9.22）的增强模块，通过**反编译 → 改 smali 注入点 → 重打包**实现。
* **两部分代码**（务必分清）：
  * **开源部分**：`mod-src/**`（我们写的 Java）、`guard-src/**`、构建/验证脚本、`docs/**`、`features.json`/`license.json` —— 全部在仓库里；
  * **闭源部分**：宿主 App 的 smali 注入骨架（`apktool-out/**`，含原版代码）—— **不进仓库**，只在 `docs/INJECTION_POINTS.md` 里给低粒度说明。
* **dex 布局**（9 个，索引必须连续，ART 只加载到第一个缺口）：
  | dex | 内容 |
  |---|---|
  | `classes.dex` … `classes6.dex` | 宿主 App 原 dex（我们只在里面插指令） |
  | `classes7.dex` | **Mod 主逻辑**（R8 全量重命名 + 字符串加密） |
  | `classes8.dex` / `classes9.dex` | **多 dex 互锁守卫**（Guard8 / Guard9） |
* **两条构建路径**（选错会白等或漏改动）：
  * 只改 `mod-src/**` → **快速路径**（约 3–5 分钟）：`obf_strings.py` → `javac` → `R8` → `inject_dexes.py` → `zipalign` → `apksigner`；
  * 改了 `apktool-out/**`（smali/资源）→ **必须 apktool 全量**（约 10–15 分钟），之后同样跑 `inject_dexes.py` → 签名。

---

## 1. 文档地图（什么时候读哪份）

| 文档 | 讲什么 | 什么时候读 |
|---|---|---|
| `README.md`（仓库根） | 项目简介、功能列表、构建步骤、授权语义 | 第一次接触 |
| **`DEVELOPMENT_GUIDE.md`（本页）** | 文档索引 + 任务→文件对照 + 禁区 + 验证清单 | **每次动手前** |
| `docs/REMOTE_CONFIG.md` | 远程授权配置字段、ID 对照、灰度/熔断示例 | 改授权、改云端开关 |
| `docs/MULTIDEX_INTERLOCK.md` | 多 dex 互锁原理、构建顺序、5 组验证断言 | 动 `guard_gen.py` / 改密钥链 / 改 dex 布局 |
| `docs/CLOUD_KEEP_ORIGINAL_DESIGN.md` | 云原片：原理、8 个注入点全表、INV 不变量、排障手册 | 动云原片任何一行 |
| `docs/TECH_NOTES_CLOUD_KEEP.md` | 云原片早期实测（成功案例逐字段分析、服务端判据） | 怀疑"服务端到底看什么" |
| `docs/UPLOAD_CHAIN_ANALYSIS.md` | 上传链路逐环节对照（官方 / 老版 / 当前） | 上传/压缩相关改动 |
| `docs/OPEN_SOURCE_SCOPE.md` | 什么开源、什么闭源、公开接口清单 | 决定"这段能不能提交" |
| `docs/INJECTION_POINTS.md` | 注入点**低粒度**说明（只有接口与约束，无类名） | 对外发布/解释 |
| `PRIVACY_DEVICE_LEAK_ANALYSIS.md` | 设备信息泄漏链分析（请求头/参数/SDK） | 动隐私相关 |
| `HOMEWORK_PUBLISH_ANALYSIS.md` | 发作业链路与数据模型 | 动布置作业修复 |
| `IDENTITY_ANALYSIS.md` | 教师身份判定的调用面 | 动身份伪装 |
| `BUILD_SELFCHECK.md` | 构建自检项 | 打包前后 |
| `DELIVERY_*.md`（多份） | 每一次交付的产物、SHA256、验证记录 | 追溯"哪版改了什么" |

---

## 2. 任务 → 文件对照表（核心）

> 规则：**新写的代码只放 `mod-src/`**；改宿主行为必须在 `apktool-out/` 里插指令，并同步更新本表提到的 keep 规则与文档。

| 我想改… | 主要文件 | 还要动 | 验证 |
|---|---|---|---|
| **面板 UI / 文案 / 分区** | `mod-src/.../XLModActivity.java` | 若新增功能区 → `XLModFeatures.IDS`、`xlmod-config/features.json`、`docs/REMOTE_CONFIG.md` | 快速构建；`grep` 新控件存在 |
| **新增/修改配置项** | `mod-src/.../XLModConfig.java`（`b()/i()/s()` + getter/setter） | 面板加控件；如需 smali 读 → 加 public static 字段 + `obf-rules.pro` | 快速构建 |
| **云端授权开关** | `mod-src/.../XLModFeatures.java` | `features.json` / `license.json`（`sign_config.py sign`+`bundle`）、`update_license.bat`、`docs/REMOTE_CONFIG.md` | `verify_remote_lock.py` + 远端 raw 比对 |
| **隐私隐藏（设备信息）** | `mod-src/.../XLModHelper.java`（`sanitizeHeaders` / `cleanDeviceInfo`）、`XLModConfig`（privacy_*） | smali：请求头拦截器·intercept()、登录管理类·家长登录入口；`XLModFeatures.ALWAYS_ON` 必须含 `privacy` | `verify_privacy_v41.py` + `verify_privacy_independent.py` |
| **云原片（绕过转码）** | `mod-src/.../XLModHelper.java`（`disguiseFileForCloud` / `spoofVideoHeader` / `disguisedMd5` / `uploadExtFor` / `prepareCloudKeepOriginal`） | smali：上传任务类（压缩入口 / 分块准备 / 整文件上传 / 两处扩展名赋值）、压缩码率工具类、压缩判定工具类、上传管理类；`obf-rules.pro` | `verify_dex_interlock.py` + 真机抓包看下载链接有无 `mp4_` 前缀；详见 `CLOUD_KEEP_ORIGINAL_DESIGN.md` §6 |
| **布置作业修复** | `mod-src/.../XLModHelper.java`（`hw*`、`capture*`、`merge*`、`injectNow`、`prefetchQuestions`） | smali：作业页 Activity（含其回调内部类）、作业页 Fragment、作业 Helper；`obf-rules.pro` | 抓包/日志 `[抓取]`、`[注入]`；`HOMEWORK_PUBLISH_ANALYSIS.md` |
| **多 dex 互锁 / 防篡改** | `guard_gen.py` → `guard-src/**` → `build_guard.py`（产出 `classes8/9.dex` + `guard-key.txt`） | `inject_dexes.py`、`guard-rules.pro`、`obf_strings.py`（密钥来源） | `verify_dex_interlock.py`（5 组断言）；⚠️ 改了生成器**必须重跑 `obf_strings.py`** |
| **字符串加密/解密** | `obf_strings.py`、`mod-src/.../Obf.java` | `obf_strings.py` 的 `FILES` 列表（新增 .java 要登记） | 解密自检（`verify_dex_interlock.py` 第 ④ 项） |
| **注入点（新增一处 hook）** | apktool-out 下对应的 smali 文件 | 目标方法所在 dex 的 **method_ids 余量**、`obf-rules.pro` keep、`docs/INJECTION_POINTS.md` | 全量 apktool + `dexdump` 确认调用存在 |
| **自动签到 / 打榜 / 答题 / 云朵** | `mod-src/.../XLModHelper.java`（`autoSignIfNeeded`、`autoOnRankResume`、`buildAutoAnswer`、`autoCloudIfNeeded`）+ `XLModConfig` | smali：主界面 Activity、答题相关页面、竞赛列表回调 等 | 运行日志里的 `[自动]` 行 |
| **日志/崩溃面包屑** | `mod-src/.../XLModConfig.java`（`logAppend`/`crashPut`）、`XLModHelper.bc()` | — | 面板「日志」导出 `/sdcard/Download/xlmod_log.txt` |

---

## 3. 硬约束与高频坑（踩过的都在这）

1. **`classes.dex` / `classes3.dex` 的 `method_ids` 已经 65536/65536（零余量）**：
   不能再给这两个 dex 里的类**新增方法引用**。需要新调用时：① 复用该 dex 里已引用的 mod 符号；或
   ② 用**反射**（引用落在有 6 万+ 余量的 `classes7.dex`）。查余量：
   ```python
   struct.unpack_from('<I', dex_bytes, 0x58)[0]   # method_ids_size，上限 65536
   ```
2. **smali 调用到的每个 mod 方法/字段都必须在 `obf-rules.pro` 的 keep 清单里**，否则 R8 改名 →
   `NoSuchMethodError`（异常被吞 → 功能**静默失效**，最难查）。
3. **字符串是加密的**：`mod-src` 里的中文并不出现在 dex 里（`obf_strings.py` 用 `guard-key.txt` 的密钥 XOR+Base64）。
   所以 **`grep dex` 查不到中文不等于没打包**；查方法名也会因 R8 改名而失败——要用 `dexdump` 查 `<clinit>`、类名、keep 符号。
4. **改了 `mod-src/**` 必须重跑 `obf_strings.py`**，否则改的字符串不会生效。
5. **改了 `guard_gen.py`（密钥链）必须按顺序重跑**：`guard_gen.py` → `build_guard.py` → `obf_strings.py` → 编译 → 打包，
   否则字符串全乱码（互锁生效）。
6. **Java 字符串里不要用 ASCII 双引号**（`"..."` 里再写 `"`），否则编译期报错/被加密脚本切坏；用 `「」`。
7. **`.bat` 的老坑**：`rem` 注释里不能出现 `> < | & ^`（会被当重定向/管道执行）；
   `for /f` 内的重定向要写 `2^>nul`；括号块里的 `^` 要写 `^^>`。
8. **`github.com:443` 时常连不通**（`raw`/`api` 可能正常）：`update_license.bat` 已内置"绕过本地代理重试"；
   推送失败时它会明确提示，本地提交不会丢。
9. **CDN 缓存**：`raw.githubusercontent.com` 是 `max-age=300`，推送后约 5 分钟才刷新；
   所以配置一律以**单文件 `license.json`** 分发（配置+签名同文件，避免"新签名配旧配置"导致误锁）。
10. **行尾/编码**：签名前会把配置规范成 LF（git 入库会转 LF，否则回退路径验签失败）；`.bat` 用 CRLF+UTF-8 BOM。
11. **不要提交**：`apktool-out/`、`jadx-out/`、`*.apk|dex|smali`、`keys/*_private.pem`（`.gitignore` 已覆盖，`precheck_push.py` 会检查）。

---

## 4. 标准工作流

```bash
# ① 改代码
#   - 只动 mod-src  → 快速路径
#   - 动了 smali    → 先 apktool 全量

# ② 快速路径（mod Java）
python obf_strings.py
javac -proc:none -source 1.8 -target 1.8 -encoding UTF-8 \
      -bootclasspath <android.jar> -classpath mod-stub-classes \
      -d obf-classes obf-src/net/xuele/xuelets/mod/*.java        # ⚠️ 必须检查退出码
java -cp r8-8.3.37.jar com.android.tools.r8.R8 --release --min-api 19 \
     --lib <android.jar> --lib mod-stub-classes --pg-conf obf-rules.pro \
     --output obf-dex <obf-classes 下的 class 文件>
python inject_dexes.py            # classes7/8/9 一起写回，并校验 dex 索引连续
zipalign -f 4 … && apksigner sign --ks xlmod.keystore …

# ③ smali 全量（改注入点时）
java -jar apktool/apktool.jar b -f -j 1 apktool-out -o xueleyun_mod_unsigned_ui.apk
# 然后接 ② 的后半段（inject_dexes → 对齐 → 签名）

# ④ 验证（按改动挑，全部要跑一遍相关的）
python verify_dex_interlock.py        # 互锁 + 密钥链
python verify_remote_lock.py          # 授权语义（含签名）
python verify_privacy_v41.py          # 隐私：请求头改写
python verify_privacy_independent.py  # 隐私：不受云端影响
python precheck_push.py               # 推送前：仓库里没有闭源/私钥内容
```

## 5. 交付要求（每次改动都要）

1. 产出**新 APK** 并记录 `size` + `sha256`；
2. 在 `DELIVERY_<版本>.md` 里写清：改了什么、**怎么验证的（贴命令与输出）**、已知取舍；
3. 改动涉及的 `docs/*.md` 同步更新（见第 1 节表格）；新功能要登记到本页第 2 节；
4. 用户可见的文案要在面板里说明"能做什么、不能做什么"（不要含糊）。

## 6. 提交规范

* 身份：`wg-1337`（本机 git 已配置；`update_license.bat` 也改为用本机身份提交）；
* 授权配置更新走：`update_license.bat [edit] [nopush]`（签名 → 打包 `license.json` → 校验 → 提交推送 → 远程复验）；
* 代码提交：先 `python precheck_push.py`，再 `git add -A && git commit && git push origin main`（必要时加
  `-c http.proxy= -c https.proxy=` 绕过失效代理）。
