# 视频上传链路 & 压缩过程 —— 三版本逐环节对照表

> 对照对象：**官方原版 5.9.22**（`xueleyun_5922_7c8cd259a8545bba6c16c0265828e273.apk`）、**V2.5**（用户提供的 `xueleyun_xlmod_5.9.22(1).zip` 内 APK，2026-09-06 14:22）、**当前版**。
> 方法：三份 APK 全部用 apktool 反编译成 smali（官方 36,342 / V2.5 36,378 / 当前 36,343 个文件），
> 用 `grep -rl "net/xuele/xuelets/mod/"` 定位注入点，再逐文件 `diff`；关键类另外用 jadx 反编译成 Java 逐行核对。

## 0. 结论先行
1. **上传入口只有一个**：`CommonApi.blockUploadInit` 与 `CommonApi.upload` 在整个官方包里**只被 `SingleFileTask` 调用** → 所有落到 `dl.xueleyun.com` 的文件都走这条链路。
2. **注入点完全一致**：V2.5 有 28 个基础类注入点，当前版**全部都有**；diff 出来只有**标签改名**，逻辑一模一样（见 §2）。
3. **"不被压缩"的开关在客户端**：`SingleFileTask.start()` 里 `isVideoSupportCompress && needCompress` 才走本地压缩；
   `needCompress = 视频实际码率 > getResolutionBitRate(档位) × 1.1`，而 `getResolutionBitRate()` 正是**老版就挂钩**的函数。
4. 因此老版能"不压缩"，只可能是：**① 压缩模式=不压缩**，或 **② 压缩模式=自定义码率且填的码率 ≥ 视频码率/1.1**（成功案例 3734kbps → 填 4000 即可）。
5. 当前版已用 **V3.1e 把「云端保持原片」直接接到"跳过本地压缩"**（`compressVideo` 分支）+ **`getResolutionBitRate` 兜底返回 100Mbps**（使 `needCompress` 恒为 false），**不再依赖面板设置**。

## 1. 完整链路与各环节行为
| # | 环节（类.方法） | 官方原版 | V2.5 (09-06) | 当前版 | 差异 |
|---|---|---|---|---|---|
| 1 | `SingleFileTask.start()` 小文件判定 | `fileLen ≤ 阈值` → `uploadFile()` | 同 | 同 | 无 |
| 2 | 视频判定 `VideoUtils.isVideoSupportCompress(path)` | 扩展名 ∈ `SUPPORT_VIDEO_EXTENSION` | 同 | 同 | 无 |
| 3 | **是否需要本地压缩** `VideoUtils.needCompress(path)` | `码率 > getResolutionBitRate(getClosedResolutionType(w,h)) × 1.1` | 同公式，但 `getResolutionBitRate` 被 Mod 挂钩 | 同公式 + V3.1e 云原片兜底(100Mbps) + 仪表日志 | **当前版：云原片开启时必为 false** |
| 4 | 压缩分支 `SingleFileTask.compressVideo()` | `compressMode==1` → 跳过（直传） | 同 | `compressMode==1` **或 云原片开启** → 跳过 | **当前版多一条跳过条件** |
| 5 | 压缩档位 `compressInLocal()` / `SingleFileTask$3.onServiceConnected()` | `compressMode==2 ? getCompressLevel() : resource.compressResolution` | 同（挂钩相同） | 同（已恢复一致） | 无 |
| 6 | 压缩码率 `VideoFormatHelper.getResolutionBitRate(level)` | 默认表：L2/L3=2Mbps、L4=3Mbps、其它=1Mbps | Mod 挂钩：`compressMode==3 && kbps>0` → `kbps×1000` | 同 + V3.1e 云原片→100Mbps | **当前版多了云原片兜底** |
| 7 | 实际压缩执行 | 服务 `SingleVideoCompressService`(AIDL) → MediaCodec 解码→GL→编码→MediaMuxer | 同（未改） | 同（未改） | 无 |
| 8 | 伪装（分块路径）`SingleFileTask.prepareSplitFile()` | 无 | 云原片开 + 视频 → `disguiseFileForCloud(file)` + `disguisedMd5(file)` | 同（幂等） | 无 |
| 9 | 伪装（整文件路径）`SingleFileTask.uploadFile()` | 无 | 同上 | 同上 | 无 |
| 10 | 伪装内容 `XLModHelper.disguiseFileForCloud` | 无 | 复制到 `xlmod_tmp/`，只改：**每个 trak 的 `tkhd` 宽高** + **`avc1/hvc1/hev1` 条目宽高** → 1280×720 | 同（已逐行核对老版反编译代码） | 无 |
| 11 | 上传扩展名（分块）`blockUploadInit(len, blocks, md5, ext, ts)` | `getFileExtension(name)` | 云原片开 + 视频 → `"bin"` | `uploadExtFor()` → `"bin"`（默认；键 `hw_upload_ext_mode_v2`） | 结果一致 |
| 12 | 上传扩展名（整文件）`upload(md5, len, file, ext, ts)` | 同上 | 同上 | 同上 | 结果一致 |
| 13 | 发作业业务层 `FileUploadManager.start()` | 官方无此注入 | 无 | **新增** `prepareCloudKeepOriginal(mFileList)`（早于 refreshFileKey/去重） | 当前版新增 |
| 14 | 服务端存储 | — | — | — | 原样保存 → `<md5>.mp4`；转码 → **`mp4_<md5>.mp4`**（实测：`md5(成功案例.mp4)` = URL 名） |

## 2. 注入点逐文件 diff 结论（V2.5 → 当前版）
| 文件 | diff 行数 | 实质差异 |
|---|---|---|
| `SingleFileTask.smali` | 84 | ①`compressVideo` 多"云原片跳过"；②扩展名改走 `uploadExtFor`（结果仍 bin）；③其余全是标签改名 |
| `SingleFileTask$3.smali` | 4 | 仅标签改名 |
| `VideoFormatHelper.smali` | 17 | 仅标签改名 + 本版新增云原片兜底 |
| `DeviceUtil / LoginManager / M_User / TeachAuthUtil / UserLimitManager / XLAlertPopup / NotificationDetailV2Activity* / Challenge*` | 5~42 | 与压缩链路无关（登录/身份/通知/打榜） |
| 其余 160 个 smali | — | 三方库（umeng/xiaomi/jpush/tencent/rong…）的 **apktool 反编译写法差异**，非补丁（例：`static final boolean VERBOSE:Z = false` vs 无初值） |

## 3. 为什么"现在可能复现不出来"——三个候选原因与判别方法
| 候选原因 | 判别方法（本版已埋仪表） |
|---|---|
| ① **面板设置不同**：成功案例必然是 `压缩模式=不压缩` 或 `自定义码率≥视频码率/1.1` | 日志新增 `[压缩判定] …` 行，直接打印 码率/档位/目标/needCompress + 当前 云原片/压缩模式/自定义码率 |
| ② **走的不是同一条链路**：融云 IM 的视频通道（`io.rong.imkit.utilities.videocompressor`）与 `SingleFileTask` 无关，本地压缩不受 Mod 控制 | 若上传时**没有** `[压缩判定]` 日志 → 说明没走 `SingleFileTask`（走的是 IM/其它通道） |
| ③ **服务端规则变化**：判定依据从"容器宽高"变成了别的 | 看下载链接：`mp4_` 前缀 = 被转码；再对比上传前后文件（我们只改了 11 字节） |

## 4. 本版（V3.1e）新增的两条保险
```smali
# VideoFormatHelper.getResolutionBitRate() 开头
if (XLModConfig.isCloudKeepOriginal()) return 100000000;   # 100Mbps → needCompress 恒 false
# VideoUtils.needCompress() 开头
XLModHelper.logNeedCompress(path);                          # 记录判定全过程
```
→ 只要「云端保持原片」开着：**不进入压缩分支**（本地不重编码）→ `prepareSplitFile` 走"伪装 + bin" →
与成功案例（原始码流 1080p/3734kbps/level51/btrt 保留 + 容器 1280×720 + 头部未被扰码）完全一致。

## 5. 上机复测步骤（看日志即可定位）
1. 面板：打开「云端保持原片」，「上传扩展名」保持 `bin`（默认）；
2. 发一条带视频的通知（或发作业带视频）；
3. 到 Mod 面板看日志，应出现：
   ```
   [压缩判定] xxx.mp4 1920x1080 码率=3734000 档位=3 目标=100000000 → needCompress=false ｜ 云原片=true 压缩模式=0 自定义码率=0
   [云原片] 容器伪装已应用(与老版本同款): xxx.mp4 → 容器宽高 1280x720（仅改 tkhd + avc1/hvc1/hev1 宽高，编码参数不动）
   ```
4. 看下载链接：`.../<md5>.mp4` = 成功；`.../mp4_<md5>.mp4` = 仍被服务端转码（把该行日志与链接发我）。
