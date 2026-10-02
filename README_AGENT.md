# README_AGENT.md — AI 协作者契约文档

> **本文档的读者是大语言模型 / 编码 Agent，不是人类。** 人类请阅读 [README.md](README.md) / [README_ZH.md](README_ZH.md)。
>
> 你（Agent）即将在一个**运行于 `system_server` 的 LSPosed 模块**项目上工作。这不是普通的应用开发：
> 本模块的 hook 代码在 Android 最关键的系统进程里执行——**这里崩溃 = 整机重启循环，可能需要刷机救援**。
> 同时，本模块的核心价值是一条安全性质："应用被欺骗，投递裁决不受影响"——破坏它比崩溃更糟，
> 因为它静默且难以察觉。
>
> 本文档包含三类知识：
> 1. **红线铁律**（§2）：惯性思维最想"优化"但绝对不能碰的代码模式，每条附违例后果；
> 2. **架构与双进程模型**（§4-§6）：快速建立正确的系统心智模型；
> 3. **验证协议**（§7-§8）：改完之后如何证明没有破坏安全性质。

---

## 0. 阅读协议（元指令）

- **权威顺序**：源码注释 > 本文档 > README。若本文档与代码注释冲突，以代码为准，并视为本文档的 bug。
- §2 的每条铁律都标注了 `[违例后果]`。这些不是代码风格偏好，是设计层面的安全契约。
- 修改任何 `.kt` / `module.prop` / manifest 之前：先过 §9 的 checklist。
- 你能做的验证：Gradle 构建（debug + release）、APK 内元数据检查、dex 符号检查、与 AOSP 源码的签名核对。真机验证须由人类执行（§8）。
- **生成代码时的默认姿态**：本项目的关键设计经常与"通用 Xposed 模块开发"的直觉相反（§3）。当你觉得"这里明显可以简化/优化"时，**先假设是你错了**，去 §2/§3 找依据；找不到再向人类提出质疑。
- **诚实性要求**：README 的验证状态表区分"源码核对"与"真机实测"。你没有做过的事，不要在文档里写成做过。

---

## 1. 项目本质（30 秒版）

**DoNotComplain = LSPosed 模块（libxposed API 102），只注入 system_server，把应用对自身通知权限的查询结果欺骗为"已开启"，而系统的投递裁决完全不受影响。**

| 事实                      | 含义                                                                                           |
|---------------------------|------------------------------------------------------------------------------------------------|
| 作用域 = `system`（静态） | 模块代码只出现在 system_server；任何应用进程零注入                                             |
| 只 hook 查询方法          | `areNotificationsEnabled*` / `checkOperation` / `check*Permission` / `getNotificationChannel*` |
| 绝不 hook 投递路径        | `enqueueNotification`、importance 检查、`PermissionHelper` 的投递侧调用——碰都不能碰            |
| 只对自查说谎              | `Binder.getCallingUid() == 被查 uid` 才拦截；否则 proceed 放行真相                             |
| system_server 崩溃 = 灾难 | 所有反射独立 try、hooker 自捕获、异常模式 protective                                           |
| 真机未测试                | v1 状态：构建验证 + AOSP 源码核对完成，真机验证待办                                            |

---

## 2. 红线铁律（RED LINES — 绝对禁止清单）

每条格式：**规则 / 为什么 / [违例后果]**。编号 RL-xx 供引用。

### A. 欺骗范围纪律（模块的核心安全性质）

- **RL-01** 只允许 hook **查询类**方法。投递裁决路径（`NMS#enqueueNotification` 及其下游、`PermissionHelper` 被投递路径调用的入口、`AppOpsService#noteOperation` 在投递链上的调用）**绝不 hook**。
  `[hook 投递 = 被禁用的通知真实送达 = 模块从"消音提醒"变成"破坏用户开关"——静默的功能性背叛]`

- **RL-02** 自查判定不可放宽。NMS/AppOps/Permission 的每条 hook 都必须保留 "calling uid == 被查 uid"（或被查 pkg ∈ calling uid 的包集合）这一条件。改成"总是返回 true"看似更简单且"反正用户也想骗系统"——**错**。
  `[Settings / SystemUI / 厂商设置界面查询任意应用时拿到假值 = 用户在系统设置里看到"通知已开启"而实际是关的 = 破坏用户对自己设备的可见性]`

- **RL-03** `isSystemInternalCall`（AppOps hooker 的调用栈检查：出现 `com.android.server.*` 帧即放行真相）**不可删除或简化**。Android < 13 的投递路径会以应用身份（uid 相同）内部调用 `checkOpNoThrow`——uid 匹配拦不住它，只有栈帧能区分。
  `[删掉后：Android 8.1-12 上被禁用的通知真实送达（同 RL-01 后果），且只在旧版本复现，极难排查]`

- **RL-04** `getNotificationChannel(s)` 的返回值**必须 Parcel 克隆后修改**，绝不原地 `setImportance`。返回的 `NotificationChannel` 可能就是 NMS `PreferencesHelper` 存储状态的那个对象。
  `[原地修改 = 系统持久化的渠道状态被真实改写 = 用户渠道设置损坏 + 重启后状态错乱]`

- **RL-05** 渠道改写只做 `IMPORTANCE_NONE → IMPORTANCE_DEFAULT` 单向提升，且只对已是 `NONE` 的生效。不要"顺手"改 `setBypassDnd`、`setSound` 等其他属性。
  `[超范围改写超出模块声明的能力面，且可能触发应用端对渠道对象的完整性比对]`

### B. system_server 稳定性纪律

- **RL-06** 所有反射查找（`loadClass` / `getDeclaredMethods`）**彼此独立**且包在 `runCatching` / `hookSafely` 里，找不到即跳过该 hook 点。不得把多个类的加载合并成一个"要么全成功要么抛异常"的初始化。
  `[OEM ROM 上某个类名漂移 = 整个模块初始化失败 = 全部 hook 静默失效；更糟的是抛到框架层的异常路径行为因 ROM 而异]`

- **RL-07** hooker lambda 内**不得**做任何可能抛异常的裸操作（反射调用、强转外部输入）而不自捕获。`module.prop` 保持 `exceptionMode` 缺省（protective）。
  `[protective 模式会吞掉 hooker 异常并放行，这是最后防线；但依赖它兜底是坏味道——每次触发都进框架错误处理路径]`

- **RL-08** hooker 内**不得**持有跨调用可变状态、**不得**开线程、**不得**做 binder 出调用（`packagesForUid` 的反射查询结果必须走 `ConcurrentHashMap` 缓存，且容忍缓存失败返回空集）。
  `[system_server 的 binder 线程池是全系统共享资源；hooker 慢/死锁 = 全系统 binder 超时]`

- **RL-09** `uidPackages` 缓存**只能增不能改**（uid → 包集合在整个开机周期内只增不减；sharedUserId 场景一个 uid 多包）。不要实现"缓存失效/刷新"逻辑。
  `[刷新需要监听包变更 = 注册系统回调 = 新的崩溃面；而错误方向的"清理"可能让已卸载重装的 uid 判定漂移]`

### C. 打包与依赖纪律

- **RL-10** 三个元数据文件路径**必须**是 `app/src/main/resources/META-INF/xposed/{java_init.list, module.prop, scope.list}`。框架以 `META-INF/xposed/java_init.list` 是否存在来判定"现代模块"。
  `[移到 META-INF/ 根下 = 框架不识别 = 模块在 LSPosed 里根本不出现（本项目真实踩过的坑，commit 1895d84 → bafab4e）]`

- **RL-11** 依赖方式不可对调：`io.github.libxposed:api` / `:annotation` 必须 `compileOnly`，`io.github.libxposed:service` 必须 `implementation`。
  `[api 打进 APK = 模块自带一份可能与框架冲突的 API 实现；service 用 compileOnly = 模块自身进程运行时 ClassNotFound]`

- **RL-12** `AndroidManifest.xml` **不手动声明** `XposedProvider`——service AAR 自带声明（authority `${applicationId}.XposedService`，exported=true），manifest 合并自动注入。手动声明会因 exported 值冲突导致构建失败（真实构建判例）。

- **RL-13** `keepRules/rules.keep` 中入口类的 keep 规则（`-keep class dont.complain.DoNotComplainEntry { *; }`）**不可移除**。
  `[R8 混淆后框架按 java_init.list 里的原名加载类 = ClassNotFound = 模块失效]`

### D. 作用域与生命周期纪律

- **RL-14** `scope.list` 内容固定为 `system`，`module.prop` 的 `staticScope=true` **不可改动**。
  `[改成 false 或添加应用包名 = 违背"仅 system_server 注入"的架构承诺，模块开始进入应用进程]`

- **RL-15** 不实现热重载（`autoHotReload` 不设、不写 `onHotReloading`/`onHotReloaded`）。这是 v1 的明确决策：system_server 里做类加载器替换的风险收益比完全倒挂，且总开关已覆盖应急回滚场景。将来若启用，须按 README 中的存档清单实施（ClassLoader 经 savedInstanceState 传递是唯一的坑点）。

- **RL-16** 入口类只在 `onSystemServerStarting` 安装 hook，**不实现** `onPackageLoaded` / `onPackageReady`（system 作用域下不会被有意义地回调），**不调用** `detach()`。
  `[在 system_server 里 detach() = 主动放弃后续生命周期 = 行为未定义]`

### E. 配置契约纪律

- **RL-17** RemotePreferences 的 group 名（`Config.PREFS_GROUP = "config"`）与 key（`KEY_ENABLED = "enabled"`）是**双进程共享契约**——hook 侧只读，UI 侧写。改动必须两侧同步。
  `[只改一侧 = 开关静默失效（hook 侧读不到新 key，回落默认值 true）]`

- **RL-18** hook 侧 `enabled()` 的回落语义：`prefs == null`（RemotePreferences 不可用，框架无 remote 能力）时**返回 true（模块生效）**。这是刻意的可用性优先决策，不要"修复"为 false。
  `[改回 false = 无 remote 能力的框架上模块永久关闭，且用户无从得知原因]`

---

## 3. 反直觉设计决策（"看起来错"但正确的代码）

当你产生"这段该重构/修复"的冲动时，先查此表：

| 直觉冲动                                         | 为什么是错的                                                      | 正确认知                                                                                         |
|--------------------------------------------------|-------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| "自查判定太保守，直接无条件返回 true 更简单"     | Settings/SystemUI 查询也走这些方法                                | uid 匹配是唯一能区分"应用自查"与"系统查询"的信号（RL-02）                                        |
| "13+ 上 NMS 查询已 deprecated，hook 可以删"      | 客户端方法仍在、binder 仍在，旧应用/旧 SDK 构建的应用继续走这条路 | 13+ 是**叠加** PermissionManager hook，不是替换（SDK_INT >= 33 才追加）                          |
| "AppOps 查调用栈太丑，性能也不好"                | uid 匹配拦不住 <13 投递路径的内部 checkOpNoThrow（同 uid）        | 栈检查是唯一判据；它在 hook 命中时才执行，非热路径（RL-03）                                      |
| "getNotificationChannel 用精确签名反射更严谨"    | OEM 的参数表有漂移（4 参/5 参、有无 conversationId）              | 方法名 + 参数个数区间匹配 + 逐参数类型校验关键位（RL-06 同源）                                   |
| "NMS 的 binder 方法直接在 NMS 类里找"            | binder 实现在 NMS 的内部类（如 `NotificationService`）里          | `collectSelfAndNested` 向下遍历两层嵌套类                                                        |
| "AppOpsService 就是 android.app.AppOpsService"   | 该类迁移过三次包名                                                | 候选表探测：`com.android.server.appop.`(11+) / `android.app.`(9/10) / `com.android.server.`(8.x) |
| "MANIFEST 里声明 Provider 更可控"                | AAR 自带声明且 exported=true，手动声明必冲突                      | 删除手动声明，靠 manifest merge（RL-12，真实构建判例）                                           |
| "RemotePreferences 不可用时模块应该安全地不生效" | 用户装框架就是为了生效；不可用时关闭 = 静默失效                   | 回落 true（可用性优先，RL-18）                                                                   |
| "uidPackages 缓存该有过期机制"                   | uid→包映射在开机周期内单调；过期机制需要系统回调                  | 只增不减（RL-09）                                                                                |
| "总开关关掉后 hook 应该 unhook 而不是每次判断"   | unhook/rehook 引入生命周期竞态；enabled() 是一次 map 读取         | 保持每 hooker 内的 enabled() 检查（成本可忽略）                                                  |
| "热重载能提升体验，加上吧"                       | system_server 里做类加载器替换，失败 = 重启循环                   | 明确不做（RL-15，v1 决策存档）                                                                   |

---

## 4. 架构地图

```
仓库根/
├── README.md / README_ZH.md        人类文档（英文/中文）
├── README_AGENT.md                 本文档
├── app/
│   ├── build.gradle.kts            依赖声明（RL-11 的落点）
│   └── src/main/
│       ├── AndroidManifest.xml     label/description + AAR 自动合并的 Provider
│       ├── java/dont/complain/
│       │   ├── DoNotComplainEntry.kt   XposedModule 入口（onSystemServerStarting）
│       │   ├── Config.kt               双进程共享常量（RL-17 的落点）
│       │   ├── MainActivity.kt         Compose UI + ModuleService（XposedServiceHelper 桥）
│       │   └── hook/SystemHooker.kt    全部 hook 逻辑（本模块的核心，376 行）
│       ├── keepRules/rules.keep    R8 keep 规则（RL-13）
│       └── resources/META-INF/xposed/
│           ├── java_init.list      入口类全限定名
│           ├── module.prop         minApiVersion=102 / staticScope=true
│           └── scope.list          system（RL-14）
```

---

## 5. 双进程模型（建立心智模型）

模块的代码在**两个完全隔离的进程**里运行，共享的只有 `dont.complain` 这个包名和 `Config` 常量：

|                   | 模块自身进程（UI 侧）                            | system_server（hook 侧）                            |
|-------------------|--------------------------------------------------|-----------------------------------------------------|
| 运行者            | `MainActivity`（Compose UI）、`XposedProvider`   | `DoNotComplainEntry` → `SystemHooker`               |
| 类加载来源        | APK 自身 + service AAR 打包的类                  | 框架的模块 classloader（提供 api 构件的运行时实现） |
| 与框架通信        | `XposedServiceHelper` → binder → `XposedService` | `XposedInterface.getRemotePreferences()`（只读）    |
| RemotePreferences | 可写（`edit().apply()` 经 binder 提交到框架）    | 只读快照 + 变化监听                                 |
| 崩溃后果          | 模块 UI 闪退，无大碍                             | **整机重启循环**                                    |

**关键认知**：hook 侧永远不要假设 UI 侧存在过。`SystemHooker.install()` 里 `getRemotePreferences` 失败是正常路径（RL-18 的回落即为此设计）。

### 5.1 数据流（配置下发）

```
UI: Switch 切换 → prefs.edit().putBoolean("enabled", b).apply()
      → binder → 框架持久化 → 推送变化
hook 侧: RemotePreferences（同一 group="config"）
      → 每次 hooker 触发时 getBoolean("enabled", true) 实时读取
      → 关闭后下一次查询即恢复真实结果（无需重启任何东西）
```

### 5.2 hook 覆盖矩阵

| Android 版本     | NMS are*                        | NMS getChannel* | AppOps checkOperation | PermissionManager check* |
|------------------|---------------------------------|-----------------|-----------------------|--------------------------|
| 8.1 – 12 (27-32) | ✅                              | ✅              | ✅（含 RL-03 栈守卫） | N/A                      |
| 13+ (33+)        | ✅（deprecated 但 binder 仍在） | ✅              | ✅（守卫保留无害）    | ✅                       |

---

## 6. 核心不变量（改动后必须依然成立）

1. **查询/投递隔离**：投递路径上没有任何被 hook 的方法（源码级核对：AOSP 13 `PermissionHelper.hasPermission` 带 `clearCallingIdentity`；<13 的 AppOps 内部调用被 RL-03 栈守卫覆盖）。
2. **自查限定**：所有谎言仅在 `Binder.getCallingUid()` 与被查目标一致时发生。
3. **无持久化副作用**：模块不写任何系统状态（RL-04 克隆纪律是这一条的体现）。
4. **零应用进程注入**：scope 恒为 system；`SystemHooker` 的代码绝不应出现在应用进程。

---

## 7. 构建验证协议（每次改动后执行）

```
# 1. 构建（debug + release 都要过）
./gradlew :app:assembleDebug :app:assembleRelease

# 2. APK 内元数据路径（RL-10 回归）
unzip -l app/build/outputs/apk/release/*.apk | grep "META-INF/xposed"
# 期望：java_init.list / module.prop / scope.list 三条

# 3. 入口类未被混淆（RL-13 回归）
unzip -p app/build/outputs/apk/release/*.apk classes.dex | strings | grep "DoNotComplainEntry"
# 期望：dont/complain/DoNotComplainEntry 存在

# 4. module.prop 内容
unzip -p app/build/outputs/apk/release/*.apk META-INF/xposed/module.prop
# 期望：minApiVersion=102 / targetApiVersion=102 / staticScope=true
```

任何一步不符合 → 回到 §2 找对应的 RL 条目。

---

## 8. 真机验证序列（人类执行；AI 负责维护此清单）

1. **启用**：LSPosed → 模块 → 启用；作用域确认 `system`（不是 `android`）。
2. **开机日志**：LSPosed 日志里出现 `Installed N hooks in system_server`（N > 0；N == 0 说明符号探测全部失败，按 §3 的候选表逐项排查该 ROM）。
3. **功能正向**：禁用应用 X 的通知 → 打开 X → 无"开启通知"引导；X 的通知确实不送达。
4. **功能反向（关键）**：设置 → 应用 X → 通知：开关显示为**关**（RL-02 的真实验证）。
5. **开关回滚**：模块 UI 关闭总开关 → X 内再查（可用 `adb shell dumpsys notification --noredact | grep` 或应用内行为）→ 恢复真实结果。
6. **渠道粒度**：关闭 X 的某个渠道 → X 内该渠道显示为默认开启状态（而非关闭）。
7. **稳定性 soak**：日常使用 24h，无 system_server 重启（`adb shell uptime` 与 `sys.boot_completed` 交叉确认）。

**若开机循环**：recovery 下禁用模块（删启用标记或直接卸载），把 LSPosed 日志带回分析——hooker 内的异常在 protective 模式下会留痕。

---

## 9. 修改代码前的 Checklist（逐项过）

```
[ ] 我改的是查询路径还是可能触及投递路径？ → RL-01
[ ] 自查判定条件是否保留？ → RL-02
[ ] AppOps hooker 的栈守卫是否还在？ → RL-03
[ ] 渠道对象是否仍然克隆后修改？ → RL-04/05
[ ] 新增反射是否独立 try + 独立降级？ → RL-06
[ ] hooker 内是否引入了新状态/线程/出调用？ → RL-08/09
[ ] 是否动了 META-INF/xposed/ 的文件位置或内容？ → RL-10/14
[ ] 依赖方式是否仍是 api=compileOnly / service=implementation？ → RL-11
[ ] manifest 是否手动声明了 Provider？ → RL-12
[ ] keep 规则是否覆盖新入口/新反射目标？ → RL-13
[ ] Config 常量改动是否两侧同步？ → RL-17
[ ] enabled() 回落语义是否被改变？ → RL-18
[ ] §7 构建验证协议是否全过？
[ ] 若行为变化：README/README_ZH 的验证状态表是否需要更新（诚实性）？
```

---

## 10. 已知边界与残余臂（勿当 bug 修）

| 项                         | 状态             | 说明                                                                                                                        |
|----------------------------|------------------|-----------------------------------------------------------------------------------------------------------------------------|
| OEM 服务类名漂移           | 探测缓解，不保证 | 候选表覆盖 AOSP 路径；极端定制 ROM 可能全部 miss（hook 计数为 0 可辨）                                                      |
| Android 13+ 客户端权限缓存 | 已知项           | `checkSelfPermission` 有进程内缓存，应用冷启动后首次查询才走 binder——欺骗对缓存之后的查询依然有效（值来自首次 binder 结果） |
| `noteOperation` 路径       | 刻意不 hook      | noteOp 携带副作用（记账+同步 binder），hook 它的收益低、风险高；`checkOperation` 已覆盖纯查询面                             |
| 多用户 / work profile      | 未专门验证       | uid 判定天然按 user 隔离（uid 含 userId），但双用户场景未测试                                                               |
| 热重载                     | v1 明确不支持    | RL-15；架构已友好（单入口、无自有线程），启用成本存档于 README                                                              |

---

## 11. 编码与提交规范

- **代码注释**：中文；契约与不变式写在紧邻代码处（本文档 §2/§3 的条目大多在源码有对应注释——修改行为时同步注释）。
- **常量集中**：双进程共享的魔法值（group/key/类名候选表/常量 ID）必须留在 `Config` 或 `SystemHooker` 顶部的常量区，不得内联到逻辑中。
- **提交粒度**：一个提交一个意图；修复 RL 违例的提交须在 message 里引用 RL 编号。
- **文档同步**：改 hook 面 / 安全性质 → 同步 README 的 Features 与原理图；改验证状态 → 同步验证表（诚实性要求见 §0）。
- **不引入**：运行时日志开关（remote prefs 已够用）、任何应用侧 hook、DexKit 之类重依赖（当前 hook 面用不到特征搜索）。

---

*本文档随代码演进同步维护。若你（Agent）发现文档与代码冲突：以代码为准，并在你的修改说明中指出文档偏差。*
