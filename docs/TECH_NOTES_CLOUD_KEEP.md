# 云原片（不被服务端转码）技术细节记录

> ⚠️ **改动此功能前先读 `CLOUD_KEEP_ORIGINAL_DESIGN.md`**（注入点全表 / keep 清单 / 不变量 / 自检清单 / 排障手册）—— 本文是早期实测分析，配方与坑以设计记录为准。

> 结论来自用户提供的**成功案例**（`压缩视频示范/成功案例.mp4`，下载链接 `http://dl.xueleyun.com/files/74c869ad581c5705684cc85d2e5cdb53.mp4`），
> 以及老版本安装包 `xueleyun_xlmod_5.9.22(1).zip`（内部 APK 2026-09-06 14:22，下称 V2.5）的 `classes7.dex` 反编译核对。

## 一、服务端存了/没存的判别方法（最直接的证据）
| 下载链接形态 | 含义 |
|---|---|
| `http://dl.xueleyun.com/files/<32位md5>.mp4` | **原样保存**（服务端没有转码）→ 成功 |
| `http://dl.xueleyun.com/files/mp4_<32位md5>.mp4` | **服务端转码产物**（多了 `mp4_` 前缀）→ 失败 |

验证：`md5(成功案例.mp4) = 74c869ad581c5705684cc85d2e5cdb53` **正好等于** URL 里的名字 →
说明服务端用**文件本身的 md5** 作为存储名（原样保存时），转码时才另存为 `mp4_<md5>.mp4`。

## 二、成功案例的文件特征（逐字段实测）
用 `python mp4_diff.py 压缩视频示范/成功案例.mp4 压缩视频示范/压缩前.mp4` 对比：

| 字段 | 成功案例（不被转码） | 压缩前（会被转码的普通上传） |
|---|---|---|
| 文件结构 | `ftyp` + `free` + `mdat` + `moov`（moov 在尾部） | 同 |
| 尾部元数据 | `Lavf59.27.100` / `Packed by Bilibili XCoder v2.0.2`（**未被扰动**） | 正常 |
| 视频轨 tkhd | **1280×720** | 1920×1080 |
| 音频轨 tkhd | **1280×720** | 0×0 |
| avc1 宽高 | **1280×720** | 1920×1080 |
| 实际码流 | 1080p，**3734 kbps**，avcC level **51**，btrt **3733687**（全部保留） | 1080p，3735 kbps，level 51，btrt 3734713 |
| 音频 | 48000Hz / 218kbps（原样） | 48000Hz / 209kbps |

**关键推论**：
1. 码流没被重编码 —— 若经过 App 本地压缩（MediaCodec 解码→GL 缩放→编码→MediaMuxer），
   level 会变成目标档位（如 3.1）、btrt 不会保留（Android 的 MediaMuxer 根本不写 `btrt`）。
   → 所以成功案例**跳过了本地压缩流程**（=「压缩模式 → 不压缩」那条分支）。
2. 只改了容器宽高，而且**连音频轨的 tkhd 都写成 1280×720** —— 与服务端判定"是否已达标"的口径吻合：
   **服务端看的是容器里声明的宽高，不是码流 SPS**（否则 1080p 的 SPS 早就触发转码了）。
3. 头尾**没有做任何扰码**（早前 `mp4_restore.py` 里的"首尾 XOR 0x5A"是更早的方案，本案例没用）。

## 三、配方（照着做）
```
选视频
  → compressVideo()：
        if (XLModConfig.getCompressMode() == 1)          → 跳过本地压缩
        if (XLModConfig.isCloudKeepOriginal())           → 跳过本地压缩     ← 成功案例走这条
        否则                                              → 正常压缩流程
  → 跳过分支：prepareSplitFile(mCurrentFile, md5)
        · file = XLModHelper.disguiseFileForCloud(file)      // 复制到 xlmod_tmp/，只改容器宽高
        · str  = XLModHelper.disguisedMd5(file)              // 用伪装副本的 md5
        · blockUploadInit/upload 的扩展名 = "bin"
  → 上传（服务端原样保存 → 下载链接为 <md5>.mp4）
```

### 伪装实现（`XLModHelper.disguiseFileForCloud` → `spoofVideoHeader`）
只做两件事，其它字节一个都不动（实测整文件仅 **11 字节**差异，`mdat` 逐字节未变）：
1. 遍历 `moov/trak`：`tkhd` 宽高 → 1280×720（v0 在 +84/+88，v1 在 +96/+100；
   注意：**每条轨道都写**，包括音频轨 —— 与成功案例一致）
2. 遍历 `moov/trak/mdia/minf/stbl/stsd`：采样条目类型 ∈ {`avc1`,`hvc1`,`hev1`} →
   条目内 +32/+34（16bit）→ 1280 / 720
3. 不碰 `avcC` / SPS / `btrt` / `colr` / `pasp`（改这些会让容器与码流自相矛盾，实测反而更容易被转码）

## 四、已经验证过"会失败"的做法（不要再走）
| 做法 | 结果 |
|---|---|
| 本地重新编码（任何档位/码率） | 码流被重编码 → level/btrt 变化，等于自己制造"没压过"的样子；用户实测"会压缩了" |
| `avcC.AVCLevelIndication` → 31、SPS `level_idc` → 31 | 容器说 720p、码流说 level 5.1，自相矛盾 → 更容易被转码 |
| `btrt` max/avg → 704kbps | 文件实际 3.7Mbps 却声明 704kbps，反而异常 |
| 上传扩展名用 `mp4`（媒体文件） | 服务端按视频处理 → 转码（用户实测"本来发通知不会压缩，现在也会了"） |
| 强制"云原片时也要走本地压缩流程" | 同上，等于放弃原画质 |

## 五、发作业链路（本 Mod 新增，老版没有）
`AssignHomeworkFragment.assign()` → `UploadTask.start()` → **`FileUploadManager.start()`** → `SingleFileTaskManager.addTask()`。
在 `FileUploadManager.start()` 顶部注入 `XLModHelper.prepareCloudKeepOriginal(mFileList)`（**早于** `UploadDataHelper.refreshFileKey()`/服务端去重）：
对列表里每个视频资源 → 路径换成伪装副本、`fileMd5` 按副本重算、`sourceMd5`/`fileKey` 清空重算、
`fileExtension` = bin（`uploadExtFor`）、`fileSize` 同步为副本长度。
之后链路末端 `SingleFileTask` 会再套一遍同一套伪装（幂等，命中缓存）。

## 六、复现与验证
```bash
# 1) 伪装参数实测（用出厂源码真跑，含真实 60MB 原片）
python test_v5_spoof_reference.py        # → ALL CHECKS PASSED

# 2) 成功案例参数对照
python mp4_diff.py "压缩视频示范/成功案例.mp4" "压缩视频示范/压缩前.mp4"

# 3) 包内静态验证（注入点/keep 符号/加密文案/条目差异）
python verify_v4_three_tasks.py          # → ALL CHECKS PASSED
```
手机上验证：开「云端保持原片」→ 发视频 → 拿到下载链接 → **没有 `mp4_` 前缀 = 成功**。
