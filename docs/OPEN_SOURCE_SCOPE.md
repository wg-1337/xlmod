# 开源范围说明（Open Source Scope）

> 本仓库**只开源 XLMod 新增的代码**；对被修改的宿主 App 代码（反编译后的 smali 补丁）**不提供源码**，
> 仅提供"注入点清单"（类名/方法/时机，不含原版代码），见 `INJECTION_POINTS.md`。

## 1. 开源（MIT / 随仓库 LICENSE）

| 路径 | 内容 |
|---|---|
| `mod-src/net/xuele/xuelets/mod/**` | Mod 全部新增 Java 代码：`XLModActivity`（面板 UI）、`XLModConfig`（配置与本地存储）、`XLModHelper`（业务实现：云原片伪装、布置作业抓取/注入、身份伪装、自动签到/打榜等）、`XLModFeatures`（远程功能区开关）、`Obf`（字符串解密 + 多 dex 密钥链） |
| `guard-src/**`（由 `guard_gen.py` 生成） | 多 dex 互锁守卫类 `Guard8` / `Guard9`（filler 链 + 密钥分片） |
| `guard_gen.py` | 守卫代码生成器（固定种子，可复现） |
| `build_guard.py` | 编译守卫 → 实跑取运行期密钥 → 分别打成 classes8/classes9 dex |
| `inject_dexes.py` | 把 classes7/8/9 一起写回 APK 并校验索引连续 |
| `verify_dex_interlock.py` | 互锁验证（5 组断言，见 `MULTIDEX_INTERLOCK.md`） |
| `obf_strings.py` | 字符串加密（XOR + Base64；密钥取自 `guard-key.txt`，缺省 0x5A 兼容模式） |
| `replace_dex7.py` | 只替换单 dex 的简化构建步骤（保留兼容） |
| `xlmod-config/**` | 远程功能开关配置文件与说明 |
| `verify_*.py` / `mp4_*.py` / `analyze_success_case.py` 等 | 校验与分析脚本 |
| 本目录下的设计文档（`CLOUD_KEEP_ORIGINAL_DESIGN.md`、`MULTIDEX_INTERLOCK.md`、`TECH_NOTES_CLOUD_KEEP.md`、`UPLOAD_CHAIN_ANALYSIS.md` 等） | 技术记录 |

## 2. 不开源（版权原因）

| 路径 | 内容 | 为什么不给 |
|---|---|---|
| `apktool-out/**` | 宿主 App 反编译出的 smali 与资源（含我们插入的注入代码） | 属于原 App 的衍生内容，受其版权约束；发布它等于分发被修改的原版代码 |
| `jadx-out/**`、`jadx/**` | 反编译结果与工具产物 | 同上 |
| `xueleyun_*.apk`、`xueleyun_mod_*.apk` | 原版/重打包产物 | 同上 |
| 任何 `smali` 补丁文件 | 我们在原版方法里插入的指令 | 同上 |

## 3. 开源部分如何"对齐"闭源部分

开源代码通过**固定名字的公开接口**被闭源的 smali 注入点调用（这样两边可以独立演进）：

```java
// 注入点调用的稳定 API（务必保持在 obf-rules.pro 的 keep 清单里）
XLModConfig.isCloudKeepOriginal()      // 云原片总开关
XLModHelper.disguiseFileForCloud(File) // 容器伪装
XLModHelper.disguisedMd5(File)         // 伪装副本 md5
XLModHelper.uploadExtFor(String)       // 扩展名（云原片开启 → "bin"）
XLModHelper.prepareCloudKeepOriginal(List)  // 发作业业务层适配
XLModHelper.hwOnAssignActivityLaunch(Activity) // 发作业页启动注入
XLModHelper.hwInjectedClassesOrNull()  // 班级注入
XLModHelper.hwEnsureSelectQuestions(Object)    // 发布前补课时/拉题
XLModHelper.ensureInit(Activity)       // 初始化入口
```

新增/修改这些签名时，必须同步更新 `obf-rules.pro` 的 keep 规则，否则 R8 改名后注入点会
`NoSuchMethodError`（异常被吞 → 功能静默失效）。

## 4. 远程功能开关与开源的关系

- 开源代码里的每个功能区都通过 `XLModFeatures.enabled("<id>")` 判断是否启用；
- 判断用的配置来自**你自己的 GitHub 仓库**（见 `xlmod-config/README.md`），因此：
  **出问题的功能可以远程关掉，不必让所有人换包。**
- 默认（拿不到配置）全部开启，不会因为网络问题把用户锁死。

## 5. 复现构建（只构建开源部分）

```bash
# 1) 字符串加密 → obf-src/
python obf_strings.py
# 2) 编译
javac -source 1.8 -target 1.8 -encoding UTF-8 -bootclasspath <android.jar> \
      -classpath mod-stub-classes -d obf-classes obf-src/net/xuele/xuelets/mod/*.java
# 3) R8（混淆 + keep）
java -cp r8-8.3.37.jar com.android.tools.r8.R8 --release --min-api 19 \
     --lib <android.jar> --lib mod-stub-classes --pg-conf obf-rules.pro --output obf-dex <classes>
# 4) 写回/签名（需要自备已注入的 APK 骨架 —— 该骨架不随仓库分发）
python replace_dex7.py
```

> 没有注入骨架时，开源代码依然可以单独编译成 dex，用于阅读、改造、提交 PR；
> 只需要保证 §3 的公开接口签名不变。
