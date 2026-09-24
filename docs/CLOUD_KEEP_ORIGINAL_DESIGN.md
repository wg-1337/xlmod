# 云端保持原片（Cloud Keep Original）—— 设计记录 / 防改坏手册

> 目的：**以后任何人（包括 AI 会话）改这个功能前先读本文件**。
> 这是一条"客户端伪装 + 扩展名伪装"绕开服务端转码的方案，任何一处漏改都会**静默失效**（不报错、只是又被压缩），
> 所以本文件把「原理 → 配方 → 全部注入点 → 不变量 → 自检 → 排障」写全。
>
> 关联文件：`TECH_NOTES_CLOUD_KEEP.md`（实测特征分析）、`UPLOAD_CHAIN_ANALYSIS.md`（上传链路逐环节对照）、
> `VIDEO_CLOUD_COMPRESS_ANALYSIS.md`（服务端转码特征）、样例视频 `压缩视频示范/成功案例.mp4`。

---

## 1. 原理：服务端凭什么"不转码"

| 现象 | 结论 |
|---|---|
| 下载 URL 形如 `http://dl.xueleyun.com/files/<32位md5>.mp4` | **服务端原样保存**（没有转码） |
| 下载 URL 形如 `http://dl.xueleyun.com/files/mp4_<32位md5>.mp4` | **服务端转码产物**（多了 `mp4_` 前缀） |
| `md5(成功案例.mp4)` = URL 里的文件名 | 服务端用**文件本身 md5** 做存储名（原样保存时） |

从成功案例（`压缩视频示范/成功案例.mp4`）逐字段实测得到的三条判据：

1. **码流未被重编码**：avcC level 仍是 `51`、`btrt = 3733687` 保留、码率 3734 kbps 未变。
   （App 的本地压缩走 MediaCodec→GL→MediaMuxer，`btrt` 根本不会写、level 会变成档位值 → 说明成功案例**跳过了本地压缩**。）
2. **只改容器宽高**：视频轨 `tkhd` 与 `avc1` 采样条目都写成 **1280×720**；**连音频轨的 `tkhd` 也写成 1280×720**。
   → 服务端判定"是否已达标"看的是**容器声明的宽高**，不是码流 SPS（否则 1080p 的 SPS 早触发转码）。
3. **没有首尾扰码**：早期"首尾 XOR 0x5A"方案（`mp4_restore.py`）在成功案例里**不存在**，该方案已废弃。

---

## 2. 成功配方（三步缺一不可）

```
① 本地不重编码      ：compressVideo() 跳过本地压缩流程（上传原文件字节）
② 容器元数据伪装    ：容器内 tkhd / avc1·hvc1·hev1 的宽高改写成 1280×720（码流、avcC、SPS、btrt 一律不动）
③ 上传扩展名 = bin  ：声明为非视频类型，服务端不按视频转码
```

面板上一个按钮就能把三步一次设好：**「一键套用成功配方（不压缩 + 容器伪装 + bin 扩展名）」**
（`compressMode=1`、`cloud_keep_original=on`、`hw_upload_ext_mode_v2=0`）。

---

## 3. 完整注入点（改完必须逐条 grep 校验）

> 行号会漂移，**以下面的 grep 关键串为准**。

| # | 位置（`apktool-out/...`） | 注入内容 | 校验命令（期望命中） |
|---|---|---|---|
| 1 | `smali_classes4/net/xuele/android/common/upload/task/SingleFileTask.smali` | `compressVideo()`：`getCompressMode()==1` 且/或 云原片开启 → **跳过本地压缩**（`goto :xlmod_skip` → `prepareSplitFile(原文件, 原md5)`） | `grep -c "isCloudKeepOriginal" SingleFileTask.smali` → **5**（compressVideo 门控 ×1 + 伪装/ext 检查 ×4） |
| 2 | 同上（`prepareSplitFile` 内，分块上传入口 `blockUploadInit`） | 云原片 + 视频 → `p1 = disguiseFileForCloud(p1)`；`p2 = disguisedMd5(p1)` | `grep -c "disguiseFileForCloud"` → **2**；`disguisedMd5` → **2** |
| 3 | 同上（`uploadFile` 内，整文件上传入口 `upload`） | 同上（文件与 md5 都要换） | 同上 |
| 4 | 同上（两处 ext 变量赋值） | `ext = uploadExtFor(name)`（云原片开启时**强制 "bin"**） | `grep -c "uploadExtFor"` → **2** |
| 5 | `smali_classes4/net/xuele/android/common/compress/info/VideoFormatHelper.smali` | `getResolutionBitRate()`：云原片开启 → 返回 100 Mbps（使 `needCompress` 恒 false，双保险）；另含 `compressMode==3 && kbps>0` 的码率覆盖 | `grep -c "isCloudKeepOriginal" VideoFormatHelper.smali` → **1** |
| 6 | `smali_classes4/net/xuele/android/common/compress/info/VideoUtils.smali` | `needCompress()` 调用 `XLModHelper.logNeedCompress(path)`（"压缩判定仪表"，只打日志不改判定） | `grep -c "logNeedCompress" VideoUtils.smali` → **1** |
| 7 | `smali_classes4/net/xuele/android/common/upload/FileUploadManager.smali` | `prepareCloudKeepOriginal(List)`：**发作业业务层**直接把资源改成：`setPath(伪装文件)`、`setFileSize(新大小)`、`setFileMd5(伪装文件 md5)`、`setFileExtension(bin)`、`setSourceMd5("")`、`setFileKey("")` | `grep -c "prepareCloudKeepOriginal" FileUploadManager.smali` → **1** |
| 8 | `mod-src/.../XLModActivity.java`（面板「视频上传压缩」） | 云原片开关 / 上传扩展名下拉 / 一键成功配方按钮 / 红字提示 | 见 §9 |

**为什么要 2 与 3 都改**：上传有小文件直传（`uploadFile`）与大文件分块（`prepareSplitFile`→`blockUploadInit`）两条路，
只改一条 → "有些视频能绕、有些不能"。
**为什么要 7**：发作业的视频走 `UploadTask → FileUploadManager`，业务层会把路径/md5/扩展名重新写一遍，
不在这一层也换掉，前面 2/3 的努力会被覆盖。

---

## 4. Mod 侧 API 契约（**R8 keep 清单，缺一个就静默失效**）

smali 注入点靠**方法名+签名**调用，R8 改名会让调用变成 `NoSuchMethodError`（被 catch 掉 → 功能静默失效）。
`obf-rules.pro` 必须始终保留：

```proguard
-keep class net.xuele.xuelets.mod.XLModHelper {
    java.io.File disguiseFileForCloud(java.io.File);     # 生成伪装副本（header spoof）
    java.lang.String disguisedMd5(java.io.File);         # 伪装副本的 md5（服务端按它校验）
    java.lang.String uploadExtFor(java.lang.String);     # 云原片开启 → 强制 "bin"
    void prepareCloudKeepOriginal(java.util.List);       # 发作业业务层适配
    void logNeedCompress(java.lang.String);              # 判定仪表
}
-keep class net.xuele.xuelets.mod.XLModConfig {
    boolean isCloudKeepOriginal();                       # 云原片总开关（obf-rules 第 30 行）
    int getCompressMode();                               # 压缩模式（第 27 行）
    int getCompressKbps();                               # 第 26 行
    int getCompressLevel();                              # 第 28 行
}
```

> 注意：`getHwUploadExtMode()` **不在** keep 清单里（只被 Mod 内部 `uploadExtFor()` 调用，同 dex 内改名一致即可），
> 所以**不要**把它加到 smali 调用里，否则会踩 INV-7。
> 校验命令：`grep -n "disguiseFileForCloud\|disguisedMd5\|uploadExtFor\|prepareCloudKeepOriginal\|logNeedCompress\|isCloudKeepOriginal" obf-rules.pro`

对应实现（`mod-src/net/xuele/xuelets/mod/XLModHelper.java`）：

| 方法 | 行为要点 |
|---|---|
| `disguiseFileForCloud(File src)` | ① 缓存命中（同路径且大小一致）直接返回；② 若传入的已是伪装副本（`sCloudDisguisedPaths`）原样返回（**幂等**）；③ 复制到 `java.io.tmpdir/<同名>`（同路径时**绝不复制**，否则自拷贝会截断文件）；④ `spoofVideoHeader()` 改容器宽高；⑤ 返回新文件；任何异常 → **返回原文件**（不阻断上传） |
| `spoofVideoHeader(File)` | 只走 `moov → trak → tkhd` 与 `trak → mdia → minf → stbl → stsd → avc1/hvc1/hev1`；`tkhd` ver0 宽高在 `+84/+88`、ver1 在 `+96/+100`（16.16 定点，写 `1280<<16 / 720<<16`）；采样条目宽高在 `entry+32/+34`（uint16）。**返回改动字段数**，0 → 打警告 |
| `disguisedMd5(File)` | 对**伪装后的文件**算 md5（带缓存，`disguise` 时会清对应缓存） |
| `uploadExtFor(name)` | `isCloudKeepOriginal()` 为真 → 直接 `"bin"`（**忽略面板下拉**）；否则按 `hw_upload_ext_mode_v2`（0=bin，1=原始媒体扩展名） |
| `prepareCloudKeepOriginal(List)` | 遍历资源，取实际路径（`getAvailablePathOrUrl` 优先，回退 `getPath`），视频文件才处理，写回 path/size/md5/ext |

---

## 5. 不变量（Invariants）——违反必然出问题

| 编号 | 不变量 | 违反后果 |
|---|---|---|
| INV-1 | **md5 必须是"伪装后文件"的 md5**，且与真正上传的字节一致 | 服务端分块校验失败 → 重传/回退压缩 → 看上去"没绕过" |
| INV-2 | 上传的文件路径必须是伪装副本 | 头没改 → 服务端按 1080p 判定 → 转码 |
| INV-3 | 绝不"自己拷自己"（`src` 与 `dst` 同路径时直接返回） | 文件被截断为 0 或半截 → 上传失败 |
| INV-4 | 只改容器元数据（tkhd / avc1·hvc1·hev1 宽高）；**不动 avcC/level/SPS/btrt** | 文件自相矛盾：播放器/服务端解析异常，或"看起来压缩过但不达标" |
| INV-5 | 云原片开启时，扩展名**一律 bin**（不看面板下拉） | 以 `.mp4` 上传 → 服务端按视频转码 |
| INV-6 | 三条上传链路（分块 / 整文件 / 业务层）**全部**要注入 | 部分入口失效（"有的视频被压、有的没被压"） |
| INV-7 | `obf-rules.pro` 的 keep 清单包含 §4 全部签名 | R8 改名 → `NoSuchMethodError` → 静默失效 |
| INV-8 | 伪装失败要**回退原文件**且不影响上传（不得抛异常给上传流程） | 上传直接失败，比被压缩更糟 |

---

## 6. 改完后的自检清单（照抄执行）

```bash
cd F:/Android/AndroidDev/project/xuelemod

# ① 注入点是否齐全（期望：5 / 2 / 2 / 2 / 1 / 1 / 1）
grep -c "isCloudKeepOriginal" apktool-out/smali_classes4/net/xuele/android/common/upload/task/SingleFileTask.smali
grep -c "disguiseFileForCloud" apktool-out/smali_classes4/net/xuele/android/common/upload/task/SingleFileTask.smali
grep -c "disguisedMd5"        apktool-out/smali_classes4/net/xuele/android/common/upload/task/SingleFileTask.smali
grep -c "uploadExtFor"        apktool-out/smali_classes4/net/xuele/android/common/upload/task/SingleFileTask.smali
grep -c "isCloudKeepOriginal" apktool-out/smali_classes4/net/xuele/android/common/compress/info/VideoFormatHelper.smali
grep -c "logNeedCompress"     apktool-out/smali_classes4/net/xuele/android/common/compress/info/VideoUtils.smali
grep -c "prepareCloudKeepOriginal" apktool-out/smali_classes4/net/xuele/android/common/upload/FileUploadManager.smali

# ② keep 清单是否还在
grep -n "disguiseFileForCloud\|disguisedMd5\|uploadExtFor\|prepareCloudKeepOriginal" obf-rules.pro

# ③ 打包后 dex 里方法是否被保留（应各有命中）
#    解出 classes7.dex 后：dexdump -d classes7.dex | grep -c disguiseFileForCloud

# ④ 伪装逻辑离线验证（拿真实视频跑一遍，看容器宽高是否被改写）
python mp4_inspect.py 压缩视频示范/压缩前.mp4          # 看改前的 tkhd/avc1 宽高
```

**运行期日志（`[云原片]` 前缀，功能正常时应全部出现）**：

```
[云原片] 上传扩展名=bin（云原片开启 → 强制伪装扩展名，绕过服务端转码）: xxx.mp4
[云原片] 容器伪装已应用: xxx.mp4 大小=… 改动字段=N 个（tkhd/avc1·hvc1 宽高 → 1280x720，编码参数与码流未动）
[云原片] 本次共适配 N 个视频资源（云原片开启 → 扩展名强制 bin，绕过服务端转码）
```

出现 `[云原片] ⚠ 未找到可伪装字段（改动 0 处）` → 说明该文件不是标准 MP4/fMP4（或 moov 结构异常），
此时**只有扩展名伪装生效**，需要单独适配。

**端到端验收（唯一可信判据）**：上传后看云端下载 URL ——
`files/<md5>.mp4` = 成功（原样保存）；`files/mp4_<md5>.mp4` = 失败（被转码）。
（md5 应与本地上传文件一致；伪装会改变字节，所以用**伪装副本**的 md5 对照。）

---

## 7. 故障排查手册（症状 → 检查顺序）

| 症状 | 按顺序检查 |
|---|---|
| 又被压缩（`mp4_` 前缀） | ① 面板是否开启了「云端保持原片」；② 日志里 `上传扩展名=bin` 是否出现（没出现 = `uploadExtFor` 没被调用 → §3 第 4 项注入丢了）；③ `容器伪装已应用 … 改动字段=N`，N=0 则容器异常；④ keep 清单 (§4) 是否完整；⑤ 业务层（发作业）是否走了 7 号注入 |
| 视频上传后软件内无法播放 | 已知取舍：扩展名 bin + 部分厂商系统库阉割（面板红字提示）；这不是"改坏了"，不要为了能播而把扩展名改回 mp4（那会立刻被转码） |
| 上传直接失败 / 校验错误 | 检查 INV-1（md5 是否取自伪装副本）、INV-3（自拷贝截断）、伪装是否回退原文件 |
| 有的视频能绕、有的不能 | 检查 INV-6：三条链路是否都注入；不同入口（小文件直传 vs 大文件分块）走不同方法 |
| 改了 Mod 代码后功能全没了 | 大概率 R8 改名（INV-7）：确认 `obf-rules.pro` keep 与 `mod-src` 方法签名**逐字一致**；`javac` 报错但打包成功也要警惕（构建链已加"编译失败即中止"） |
| `dexdump` 里查不到方法名 | 正常：方法体在 `classes7.dex`，**Mod 内部**方法会被改名，但 §4 的 5 个方法**必须能查到**（keep 的结果） |

---

## 8. 历史坑（别再踩）

1. **首尾扰码方案（XOR 0x5A + `mp4_restore.py`）**：早期试过，成功案例里没有这种扰动 → **已废弃**，不要再引入。
2. **扩展名下拉文案误导**：曾把"媒体文件（原扩展名）"标成"默认"，用户一选就失效 → 现改为**云原片开启时强制 bin**，下拉只影响"云原片关闭"的情况。
3. **只改扩展名不改容器**：服务端仍按容器声明的 1080p 判定 → 依旧转码。
4. **只改容器不改扩展名**：服务端按视频类型处理 → 依旧转码。**两者必须同时**。
5. **md5 用原件算**：分块上传校验失败。
6. **自拷贝**：同一路径复制会让文件被截断（已加 `sCloudDisguisedPaths` + 路径相等判断）。
7. **只注入一条上传链路**：出现"部分视频被压"的假象。
8. **忘记 keep 规则**：R8 改名后调用抛 `NoSuchMethodError`，异常被吞 → 静默失效（最难查的一类）。
9. **压缩模式前提**：早期实现要求 `compressMode==1` 才跳过本地压缩；现在改为"云原片开启即跳过"（`VideoFormatHelper.getResolutionBitRate` 再兜底 100Mbps），避免用户忘记设模式。

---

## 9. 配置键 / 面板 UI 对照

| 面板元素 | 配置键（SharedPreferences `xlmod_cfg`） | 取值 | 说明 |
|---|---|---|---|
| 云端保持原片（头部伪装） | `cloud_keep_original` | bool，默认 `false` | 总开关：跳过本地压缩 + 容器伪装 + 强制 bin |
| 上传扩展名（云原片开启时强制 bin） | `hw_upload_ext_mode_v2` | `0`=bin（默认），`1`=原始媒体扩展名 | 仅在云原片关闭时生效 |
| 压缩模式 | `compress_mode` | `0`=原版行为，`1`=不压缩，`2`=固定档位，`3`=自定义码率 | 「一键成功配方」会设为 `1` |
| 固定档位 / 自定义码率 | `compress_level` / `compress_kbps` | int | 仅压缩模式 2 / 3 使用 |
| 一键套用成功配方（按钮） | 同时写上面三项 | — | 写日志 `[云原片] 已一键套用成功配方: …` |

---

## 10. 构建 / 打包（两种路径，别搞混）

```powershell
# A. 只改 Mod Java（mod-src/**）—— 快（约 3~5 分钟），smali 不动
& "C:\Python314\python.exe" obf_strings.py                    # 字符串加密 → obf-src/
javac -source 1.8 -target 1.8 -encoding UTF-8 -bootclasspath <android.jar> `
      -classpath mod-stub-classes -d obf-classes obf-src/.../*.java   # 必须检查退出码
java -cp r8-8.3.37.jar com.android.tools.r8.R8 --release --min-api 19 `
      --lib <android.jar> --lib mod-stub-classes --pg-conf obf-rules.pro --output obf-dex <classes>
python replace_dex7.py                                        # 把 obf-dex/classes.dex 塞进 xueleyun_mod_injected_ui.apk 的 classes7.dex
zipalign -f 4 … ; apksigner sign --ks xlmod.keystore …        # 签名 + 校验 v1/v2/v3

# B. 改了 smali（apktool-out/**）—— 慢（约 10~15 分钟）
java -jar apktool/apktool.jar b -f -j 1 apktool-out -o xueleyun_mod_unsigned_ui.apk
# 然后接着走 replace_dex7.py → zipalign → apksigner
```

> ⚠️ 教训：`javac` 失败时若脚本不检查退出码，会拿**旧的** `obf-classes`/`obf-dex` 继续打包，
> 产出"看起来成功、其实没带上改动"的包。构建脚本必须先校验 `javac`/R8 的退出码与产物存在。

---

## 11. 相关文件一览

| 类型 | 路径 |
|---|---|
| Mod 实现 | `mod-src/net/xuele/xuelets/mod/XLModHelper.java`（`disguiseFileForCloud` / `spoofVideoHeader` / `disguisedMd5` / `uploadExtFor` / `prepareCloudKeepOriginal` / `logNeedCompress`） |
| 配置 | `mod-src/net/xuele/xuelets/mod/XLModConfig.java`（`isCloudKeepOriginal` / `getHwUploadExtMode` / 压缩相关） |
| 面板 | `mod-src/net/xuele/xuelets/mod/XLModActivity.java`（「视频上传压缩」分组 + 一键成功配方） |
| 混淆 keep | `obf-rules.pro` |
| 注入点（app 侧） | `apktool-out/smali_classes4/net/xuele/android/common/upload/task/SingleFileTask.smali`、`.../upload/FileUploadManager.smali`、`.../compress/info/VideoUtils.smali`、`.../compress/info/VideoFormatHelper.smali` |
| 分析/验证工具 | `mp4_inspect.py`、`mp4_diff.py`、`analyze_success_case.py`、`find_cloud.py`、`verify_avatar_patch.py`（对比 dex 指令数的方法可复用） |
| 样例视频 | `压缩视频示范/成功案例.mp4`（不被转码）、`压缩前.mp4`（会被转码）、`压缩后.mp4`（服务端转码产物） |
| 相关笔记 | `TECH_NOTES_CLOUD_KEEP.md`、`UPLOAD_CHAIN_ANALYSIS.md`、`VIDEO_CLOUD_COMPRESS_ANALYSIS.md` |

---

## 12. 一句话总结

> **不重编码 + 只改容器宽高（tkhd/avc1→1280×720）+ 扩展名一律 bin + 三条上传链路都注入 + md5 用伪装副本算 + keep 清单不许丢。**
> 任何一条被破坏，表现都是"功能好像没坏，但视频又被压缩了"。
