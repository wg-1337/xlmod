# 多 dex 互锁（防删 / 拖慢逆向）设计说明

> 目标：把 Mod 的**真实代码分片到多个 dex**，并插入大量**环环相扣但结果无用**的计算，
> 使得**删掉任何一个 dex 都会让 Mod 整体失效**。运行期代价几乎为零，静态分析与"删减重打包"的代价很高。

## 1. 结构

| dex | 内容 | 说明 |
|---|---|---|
| `classes7.dex` | Mod 主逻辑（`XLModActivity` / `XLModHelper` / `XLModConfig` / `XLModFeatures` / `Obf`） | R8 全量重命名 + 字符串加密（密钥不再是常量） |
| `classes8.dex` | `net.xuele.xuelets.mod.guard.Guard8` | 守卫 A：约 300 行 filler 链 + `a()` / `tag()` |
| `classes9.dex` | `net.xuele.xuelets.mod.guard.Guard9` | 守卫 B：约 300 行 filler 链 + `b()` / `tag()` |

> ART 只按 `classes.dex, classes2.dex …` 顺序加载到**第一个缺口**，所以三个 dex 的索引必须连续
> （构建脚本 `inject_dexes.py` 会做连续性断言）。

## 2. 互锁怎么成立

```
字符串解密密钥  key = 0x5A ^ Guard8.a() ^ Guard9.b()

Guard8.a() = ( chain8() ^ Guard9.tag() ) & 0x7FFF      ← 反射读 Guard9
Guard9.b() = ( chain9() ^ Guard8.tag() ) & 0x7FFF      ← 反射读 Guard8
chain8()/chain9() = 12~14 个 filler 方法串联（acc = acc*7 + fN()），结果与固定常量异或
```

* **删掉 classes8.dex**：`Class.forName("…Guard8")` 抛异常 → `Obf.key()` 返回 `0` → 所有加密字符串解成乱码 → Mod 面板、提示、日志全废。
* **删掉 classes9.dex**：`Guard8.a()` 反射失败 → 走 `peer = 0` 分支 → 分片值错误 → 同样全废。
* **想改守卫值绕过**：必须同时改两个 dex 且保证 `a()`/`b()` 与构建期加密用的密钥一致；
  而密钥是**构建期实跑守卫代码**取出来的（`build_guard.py` 里的 `KeyProbe`），
  手工改 dex 后必然对不上（`verify_dex_interlock.py` 第 ④⑤ 项就是在证明这一点）。
* **filler 代码"有意义却无用"**：每个 filler 是真实的位运算/散列/状态机，返回值参与密钥链，
  所以不能简单删除（删了分片就变），但对外部语义没有任何副作用。

## 3. 构建链（顺序不能乱）

```bash
python guard_gen.py        # ① 生成 guard-src/（含 filler 与 tag 常量）+ guard-consts.json
python build_guard.py      # ② 编译守卫 → 运行 KeyProbe 取真实密钥 → guard-key.txt
                           #    并把 Guard8/Guard9 分别打成 guard-dex/classes8.dex、classes9.dex
python obf_strings.py      # ③ 用 guard-key.txt 的密钥加密所有字符串 → obf-src/
javac … obf-src → obf-classes          # ④ 编译主逻辑（-proc:none 必须加，本机环境有坏注解处理器）
java … R8 --pg-conf obf-rules.pro --output obf-dex   # ⑤ R8 → obf-dex/classes.dex（= classes7.dex）
python inject_dexes.py     # ⑥ 把 classes7/8/9 一起写回 APK（STORED，校验索引连续）
zipalign … && apksigner sign …          # ⑦ 对齐 + 签名（v1/v2/v3）
python verify_dex_interlock.py          # ⑧ 验证互锁（见下）
```

⚠️ 注意：
1. `guard_gen.py` 每次运行都会用固定种子生成**同样的**常量（可复现），但一旦改动生成器/种子，**必须重跑 ③**（否则密钥对不上，所有字符串变乱码）；
2. `build_guard.py` 写出的 `guard-key.txt` 是 ③ 的唯一密钥来源；缺文件时 ③ 会退回 `0x5A`（老版兼容模式，此时互锁不成立）；
3. `obf-rules.pro` 里 `net.xuele.xuelets.mod.guard.Guard8/Guard9` 的 keep 规则不能删（否则 R8 改名，反射取不到）。

## 4. 验证（`verify_dex_interlock.py` 的 5 组断言）

| # | 断言 | 意义 |
|---|---|---|
| ① | `classes.dex … classes9.dex` 索引连续，三片都在 | 少一片 ART 加载就断 |
| ② | `Guard8` 只在 classes8、`Guard9` 只在 classes9（没被合并进 classes7） | 否则删 8/9 也无所谓 |
| ③ | classes7 里含 Guard8/Guard9 名字；两守卫互相引用 | 反射链真实存在 |
| ④ | 用 `guard-key.txt`（构建期实跑得到的 0x1A）能把 obf-src 密文还原成 mod-src 原文 | **密钥链与端上一致** |
| ⑤ | 用 0 / 0x5A 解码得到乱码；且 `0x5A^C8^C9` 与真实 key 不同 | 删 dex / 猜常量都失败 |

实测结果（本轮）：

```
① dex 索引连续（1..9）                OK
② Guard8→classes8, Guard9→classes9    OK（未合并进 classes7）
③ 反射引用双向存在                     OK
④ key=0x1A 还原原文（教师身份 / 自动签到 …）OK
⑤ 错误密钥 → 乱码；真实 key≠朴素推导    OK
```

## 5. 与"开源"的关系

* `guard_gen.py` / `build_guard.py` / `inject_dexes.py` / `guard-src/**` 全部属于**新增代码，随仓库开源**；
* 唯一闭源的是宿主 App 的 smali 注入骨架（版权原因），见 `OPEN_SOURCE_SCOPE.md`；
* 别人拿开源部分可以完整复现"三 dex 互锁"，只是需要自备注入骨架。

## 6. 已知取舍

* 三个 dex 都是 STORED（不压缩），APK 体积 ≈ 原大小 + 9 KB；
* 反射只发生在**首次解密**时（结果缓存），运行期开销可忽略；
* 如果将来 Mod 代码量增大，可以按同样方式加 `classes10.dex`（`guard_gen.py` 里加一个分片即可），
  但要同步更新 `inject_dexes.py` 的列表与验证脚本。
