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

- **RL-11** （历史条目，UI 移除后收敛为 RL-18）依赖方式：api 必须 `compileOnly`；service 曾以 `implementation` 服务 UI 进程，现已无需。
  `[api 打进 APK = 模块自带一份可能与框架冲突的 API 实现]`

- **RL-12** `AndroidManifest.xml` **不手动声明** `XposedProvider`——service AAR 自带声明（authority `${applicationId}.XposedService`，exported=true），manifest 合并自动注入。手动声明会因 exported 值冲突导致构建失败（真实构建判例）。

- **RL-13** keep 规则采用 DFS 式写法：`-keep,allowobfuscation ... public class * extends io.github.libxposed.api.XposedModule { public <init>(...); }` + `-adaptresourcefilecontents META-INF/xposed/java_init.list`。**两者必须成对**：入口允许混淆的前提是 java_init.list 内容随混淆名自动改写。若只保留 allowobfuscation 而丢了 adapt 行 = 框架按旧类名加载 = ClassNotFound。
  `[实测：release 中入口混淆为 Lh;，java_init.list 内容同步变为 h——配对生效]`

### D. 作用域与生命周期纪律

- **RL-14** `scope.list` 内容固定为 `system`，`module.prop` 的 `staticScope=true` **不可改动**。
  `[改成 false 或添加应用包名 = 违背"仅 system_server 注入"的架构承诺，模块开始进入应用进程]`

- **RL-15** 热重载按 DisableFlagSecure 模式实现（`autoHotReload=true`），**实现方式不可偏离三要素**：
  1. 所有 hook 经 `hookSafely` 以 `method.toGenericString()` 为 id 注册（同 id 重装 = 原子替换）；
  2. `onHotReloading` 把 system_server 的 `ClassLoader` 存入 saved state（宿主对象，非模块类加载器产物——唯一可安全跨代传递的载体）并返回 true；
  3. `onHotReloaded` 用取回的 ClassLoader 重装 hook，再卸载 `oldHookHandles` 中 id 不在新 `hookedIds` 集合里的 hook。
     `[缺任何一环：要么 hook 双份叠加（无 id 替换），要么新代码拿到失效 ClassLoader 抛异常，要么旧 hook 残留指向已卸载的旧代类——都是重启循环级风险。v1 曾裁决"不做热重载"，DFS 的生产实现证明该模式可行，据此反转]`

- **RL-16** 入口类只在 `onSystemServerStarting` 安装 hook，**不实现** `onPackageLoaded` / `onPackageReady`（system 作用域下不会被有意义地回调），**不调用** `detach()`。
  `[在 system_server 里 detach() = 主动放弃后续生命周期 = 行为未定义]`

### E. 无 UI 纪律

- **RL-17** 模块**不含任何 Activity / UI / 配置 / RemotePreferences**（对齐 DisableFlagSecure 的 release 形态）。LSPosed 的启用开关是唯一开关：启用 = 欺骗全部应用的自身查询，禁用 = 恢复全部真实结果。不要"顺手"加回设置界面、按应用开关或 service 依赖——那会重新引入 UI 进程、XposedProvider、Compose 工具链（RL-19 曾因 Compose 引发一次真机崩溃）与 APK 体积膨胀（60 KB → 12 MB）。
  `[加回 UI = 重新引入整套 Compose 工具链版本耦合与 release-only 崩溃面；"启用即全局生效"就是本模块的全部产品语义]`

- **RL-18** 依赖清单只能是 `compileOnly(libs.libxposed.api)`。`service` 构件仅服务于模块自身 UI 进程（本项目已无 UI）；`annotation` 构件未被代码引用。
  `[多一个打包依赖 = 多一个与框架运行时冲突的候选；api 打进 APK 会与框架自带实现冲突]`

### F. 工具链版本纪律

- **RL-19** **历史教训（本项目已无 Compose，规则存档备查）**：AGP 9 上 `kotlin-compose` 插件会把自己的 KGP 带上 classpath（Gradle 取最高版本），因此 toml 的 `kotlin` 条目决定实际编译器版本；真正的兼容约束在 Compose 编译器 ↔ 运行时（composeBom）之间——**kotlin 与 composeBom 必须一起升降，BOM 不得早于该 Kotlin 发布期**。
  AGP 9 的实际工具链机制（曾两度误诊，以 ScreenshotFaker 实测为准）：
  1. AGP 9 禁止应用 KGP（`org.jetbrains.kotlin.android` 会被直接拒绝），Kotlin 编译走 built-in 路径，AGP POM 内嵌 KGP 2.2.10——**但这不是有效版本**；
  2. `kotlin-compose` 插件（marker → `compose-compiler-gradle-plugin`）对自己的 KGP 是 **compile 依赖**，会被带上 build classpath，Gradle 冲突解析**取最高版本**——因此 toml 的 `kotlin` 条目实际决定了整个编译工具链（2.4.20 条目 = Kotlin 2.4.20 + Compose 编译器 2.4.20）。这就是 AGP 9 上能用 2.4.x 的原因，也是 `KotlinVersion.KOTLIN_2_4` 能编译通过的原因；
  3. **真正的兼容约束在 Compose 编译器 ↔ Compose 运行时（BOM）之间**：编译器生成的代码要求运行时不低于其最低支持版本。运行时**新于**编译器 = 安全；**旧于** = 构建全绿，运行时首个 Composable 组合即崩溃（NoSuchMethodError 族）= "模块 UI 打不开"。
     `[本项目真实事故：kotlin 2.4.20 + composeBom 2026.02.01（2 月运行时撑不起 2.4.20 编译器的生成代码）→ UI 崩溃。对照：ScreenshotFaker 用 2.4.10 + 2026.08.00 正常运行]`
     `[曾用 "锁 2.2.10 = AGP 内嵌版本" 修复——结论错误但碰巧有效：旧编译器 + 任何较新运行时 = 兼容方向。正确修复是 kotlin 2.4.20 + BOM 2026.09.00]`
     升级规则：动 `kotlin` 时，把 `composeBom` 升到与该 Kotlin 版本同期或更新的 BOM（如 2.4.20 → 2026.09.00）；动 `composeBom` 时无此约束（运行时变新总是安全方向，但注意行为变更）。

- **RL-20** release 构建的 `optimization {}` **不得设置 `packageScope` 覆盖 `androidx.**` / `kotlin.**` / `kotlinx.**`**。本项目曾用模板生成的 `packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")`，导致 debug 正常、release 点击即闪退（混淆 stdlib/androidx 是头号嫌疑）；对齐 ScreenshotFaker 的工作配置（`optimization { enable = true }`，无 packageScope）后移除。未上真机验证前，不要往 release 混淆配置里加任何"看起来更优化"的项。
  `[packageScope 混淆依赖库 = release-only 运行时崩溃，构建零警告]`

---

## 3. 反直觉设计决策（"看起来错"但正确的代码）

当你产生"这段该重构/修复"的冲动时，先查此表：

| 直觉冲动                                           | 为什么是错的                                                                                                      | 正确认知                                                                                                          |
|----------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| "自查判定太保守，直接无条件返回 true 更简单"       | Settings/SystemUI 查询也走这些方法                                                                                | uid 匹配是唯一能区分"应用自查"与"系统查询"的信号（RL-02）                                                         |
| "13+ 上 NMS 查询已 deprecated，hook 可以删"        | 客户端方法仍在、binder 仍在，旧应用/旧 SDK 构建的应用继续走这条路                                                 | 13+ 是**叠加** PermissionManager hook，不是替换（SDK_INT >= 33 才追加）                                           |
| "AppOps 查调用栈太丑，性能也不好"                  | uid 匹配拦不住 <13 投递路径的内部 checkOpNoThrow（同 uid）                                                        | 栈检查是唯一判据；它在 hook 命中时才执行，非热路径（RL-03）                                                       |
| "getNotificationChannel 用精确签名反射更严谨"      | OEM 的参数表有漂移（4 参/5 参、有无 conversationId）                                                              | 方法名 + 参数个数区间匹配 + 逐参数类型校验关键位（RL-06 同源）                                                    |
| "NMS 的 binder 方法直接在 NMS 类里找"              | binder 实现在 NMS 的内部类（如 `NotificationService`）里                                                          | `collectSelfAndNested` 向下遍历两层嵌套类                                                                         |
| "AppOpsService 就是 android.app.AppOpsService"     | 该类迁移过三次包名                                                                                                | 候选表探测：`com.android.server.appop.`(11+) / `android.app.`(9/10) / `com.android.server.`(8.x)                  |
| "MANIFEST 里声明 Provider 更可控"                  | AAR 自带声明且 exported=true，手动声明必冲突                                                                      | 删除手动声明，靠 manifest merge（RL-12，真实构建判例）                                                            |
| "RemotePreferences 不可用时模块应该安全地不生效"   | （历史条目，UI 已移除）用户装框架就是为了生效                                                                     | 无配置设计：启用即生效（RL-17）                                                                                   |
| "uidPackages 缓存该有过期机制"                     | uid→包映射在开机周期内单调；过期机制需要系统回调                                                                  | 只增不减（RL-09）                                                                                                 |
| "应该有应用内开关 / 按应用控制"                    | 系统级裁决修饰器不是 per-app 定制器；LSPosed 启用开关已是天然开关                                                 | 无 UI 无配置（RL-17，对齐 DisableFlagSecure）                                                                     |
| "热重载能提升体验，加上吧"                         | （已实现）但实现必须走 DFS 三要素，不能自创                                                                       | RL-15：id 注册 + ClassLoader 跨代传递 + 陈旧 hook 卸载                                                            |
| "kotlin 版本升级 = 常规依赖升级，只动 kotlin 条目" | compose 编译器随 kotlin 走，但 Compose 运行时由 composeBom 决定；两者错位（编译器新、运行时旧）= 运行时崩溃       | kotlin 与 composeBom 成对升降，BOM 不早于 Kotlin 发布期（RL-19）                                                  |
| "AGP 9 内嵌 Kotlin 2.2.10，所以只能用 2.2.10"      | compose 插件把自己的 KGP 带上 classpath（compile 依赖），Gradle 取最高版本——toml 的 kotlin 条目才是有效工具链版本 | 2.4.20 可用（ScreenshotFaker 同机制实测）；但**不要**因此尝试应用 `org.jetbrains.kotlin.android`（被 AGP 9 拒绝） |

---

## 4. 架构地图

```
仓库根/
├── README.md / README_ZH.md        人类文档（英文/中文）
├── README_AGENT.md                 本文档
└── app/
    ├── build.gradle.kts            唯一依赖 compileOnly(libxposed.api)（RL-11/18 的落点）
    └── src/main/
        ├── AndroidManifest.xml     裸 application（label/description，无任何组件）
        ├── java/dont/complain/
        │   ├── DoNotComplainEntry.kt   XposedModule 入口（onSystemServerStarting + 热重载）
        │   └── hook/SystemHooker.kt    全部 hook 逻辑（本模块的核心）
        ├── keepRules/rules.keep    DFS 式 keep + adaptresourcefilecontents（RL-13）
        └── resources/META-INF/xposed/
            ├── java_init.list      入口类全限定名（构建时随混淆名自动改写）
            ├── module.prop         minApiVersion=102 / staticScope=true / autoHotReload=true
            └── scope.list          system（RL-14）
```

---

## 5. 单进程模型（建立心智模型）

模块代码**只存在于一个进程**：system_server（scope=system，无 UI 进程）。
"两个进程"是历史形态（曾有 Compose UI），已随无 UI 化移除。

| 维度             | 现状                                                             |
|------------------|------------------------------------------------------------------|
| 运行者           | `DoNotComplainEntry` → `SystemHooker`，仅 system_server          |
| 类加载来源       | 框架的模块 classloader（提供 api 构件的运行时实现）              |
| 与框架通信       | 仅 `XposedInterface`（log / hook / deoptimize）；无 service 依赖 |
| 配置             | **无**——LSPosed 启用开关即唯一开关（RL-17）                      |
| 崩溃后果         | **整机重启循环**                                                 |

### 5.1 hook 覆盖矩阵

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
5. **禁用回滚**：LSPosed 中禁用模块 → 重启 → X 内查询恢复真实结果（无应用内开关）。
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
[ ] 依赖是否仍只有 compileOnly(libxposed.api)？ → RL-11/18
[ ] manifest 是否手动声明了 Provider？ → RL-12
[ ] keep 规则是否覆盖新入口/新反射目标？ → RL-13
[ ] 是否引入了 UI / Activity / 配置？ → RL-17（不允许）
[ ] 热重载三要素是否完整？ → RL-15
[ ] 是否动了 kotlin 条目？ → composeBom 是否同步升到同期或更新（RL-19）
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
- **常量集中**：魔法值（类名候选表 / AppOps 码 / importance 值）必须留在 `SystemHooker` 顶部的常量区，不得内联到逻辑中。
- **提交粒度**：一个提交一个意图；修复 RL 违例的提交须在 message 里引用 RL 编号。
- **文档同步**：改 hook 面 / 安全性质 → 同步 README 的 Features 与原理图；改验证状态 → 同步验证表（诚实性要求见 §0）。
- **不引入**：UI / Activity / 配置体系（RL-17）、任何应用侧 hook、DexKit 之类重依赖（当前 hook 面用不到特征搜索）。

---

*本文档随代码演进同步维护。若你（Agent）发现文档与代码冲突：以代码为准，并在你的修改说明中指出文档偏差。*
