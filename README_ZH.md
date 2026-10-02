# DoNotComplain

简体中文 | [English](README.md) | [AI 协作文档](README_AGENT.md)

## 📖 介绍

**DoNotComplain** 是一个 LSPosed 模块（现代 `libxposed` API 102），让应用以为自己的通知权限**已开启**——当你刻意关闭了某应用的通知后，它不再骚扰你：不再弹权限提示，不再在界面里挂着"开启通知"的横幅。

模块**只注入 `system_server`**（作用域静态固定为虚拟包 `system`），不向任何应用进程注入一个字节。应用查询"我有没有通知权限"的所有合法路径，最终都汇聚到 system server 的少数几个查询方法——`NotificationManagerService`、`AppOpsService`、`PermissionManagerService`。模块把这些查询的答案改写为 `true` / `MODE_ALLOWED` / `PERMISSION_GRANTED`，**但仅当调用方查询的是它自己时**。实际的投递裁决完全不受影响：你禁用的通知依然是禁用的，和你设置的一模一样。

项目名即全部设计：**应用不应该抱怨用户已经做出的决定**。

> **使用 AI 助手（Copilot / Claude / GPT 等）参与本项目的二次开发？** 请先阅读 [README_AGENT.md](README_AGENT.md)——它专为 AI 编写，包含本项目的**安全不变量与红线铁律**（只 hook 查询路径、只对自查说谎、投递路径隔离契约）。跳过它直接改代码，极可能破坏模块的核心安全性质，或导致 `system_server` 崩溃。

## ✨ 功能特性

- **仅注入 system_server**
  作用域静态为 `system`（`staticScope=true`，`scope.list=system`）。任何应用进程都不被触碰——应用内的完整性检查、`/proc/self/maps` 扫描都找不到模块的痕迹。这也是通用性的来源：一套 hook 覆盖设备上*所有*应用，包括现在与将来安装的。

- **四层查询覆盖**（签名已与 AOSP 8.1 / 13 源码核对）
  - `NotificationManagerService#areNotificationsEnabledForPackage` / `#areNotificationsEnabledForChannel` / `#areChannelsEnabled`——`NotificationManager.areNotificationsEnabled()` 与 `NotificationManagerCompat.areNotificationsEnabled()`（几乎所有应用和推送 SDK 的用法）的终点；
  - `AppOpsService#checkOperation`——覆盖直接用 `AppOpsManager` 探测 `OP_POST_NOTIFICATION` 的应用；
  - `PermissionManagerService#checkPermission` / `#checkUidPermission`（Android 13+）——`POST_NOTIFICATIONS` 运行时权限的查询路径；
  - `NotificationManagerService#getNotificationChannel(s)`——渠道粒度：被用户关闭的渠道上报为 `IMPORTANCE_DEFAULT` 而非 `IMPORTANCE_NONE`。

- **只欺骗查询——绝不欺骗裁决**
  投递路径（`NMS#enqueueNotification` → importance 检查 → 丢弃）与被 hook 的查询方法**零代码共享**。Android 13+ 上，投递侧检查甚至运行在 `clearCallingIdentity()` 之下（calling uid = system），与自查谎言结构性隔离。**应用以为通知开着；系统继续执行你的关闭开关。**

- **只对"自查"说谎**
  所有 hook 仅在 binder calling uid 与被查询的 uid 一致（或被查包属于该 uid）时才生效。Settings、SystemUI、任何系统组件查询*其他*应用时，永远拿到真实状态——你的通知设置页不会骗你。

- **AppOps 投递路径守卫**
  Android < 13 上，投递路径会*以应用身份*内部调用 `checkOpNoThrow`。AppOps hook 检查调用栈：任何来自 `com.android.server.*` 帧的调用都放行真实结果——谎言绝不泄漏进裁决。

- **绝不原地修改系统对象**
  `getNotificationChannel` 返回的可能是 `NMS` 存储状态的同一个对象。模块通过 `Parcel` 往返克隆渠道后改写副本——系统持久化的渠道状态绝不被修改。

- **零界面、零配置**（对标 [DisableFlagSecure](https://github.com/LSPosed/DisableFlagSecure)）
  模块不含任何 Activity。LSPosed 的启用开关就是开关：启用 = 全局欺骗所有应用的自身查询；禁用 = 立即恢复全部真实结果。无需配置、无需打开，release APK 约 60 KB，唯一依赖为 `compileOnly`。

- **支持热重载**
  `autoHotReload=true`：模块在 system_server 内自我更新，无需重启设备。hook 以稳定 id（`Executable.toGenericString()`）注册，重载时同 id 原子替换、陈旧 hook 自动卸载，system ClassLoader 经 saved state 跨代传递。

- **容错 OEM 符号探测**
  `AppOpsService` 在 Android 各版本间迁移过三次包名（8.x → 9/10 → 11+）；模块按候选表探测，按方法名 + 参数形态匹配 binder 方法（而非精确签名），且每个 hook 点彼此独立——一个符号缺失只禁用该点，不影响其余。

## 📐 欺骗的工作原理

```
  应用进程（绝不注入）                    system_server（唯一注入点）
 ─────────────────────────────────────────────────────────────────────
  NotificationManager
    .areNotificationsEnabled()    ─binder→  NMS#areNotificationsEnabledForPackage
  NotificationManagerCompat                     │ 自查？      → true   （谎言）
    .areNotificationsEnabled()    ─ 同路 →     │ 其他 uid？ → proceed（真相）
  AppOpsManager
    .checkOpNoThrow(OP_POST_NOT.) ─binder→  AppOpsService#checkOperation
                                               │ 自查 && 调用栈无 server 帧
  Android 13+ 运行时权限查询        ─binder→    │   → MODE_ALLOWED
    （POST_NOTIFICATIONS）                    PermissionManagerService#check*
                                               │ 其余 → proceed（真相）
  渠道状态查询                     ─binder→  NMS#getNotificationChannel(s)
                                               → 克隆，IMPORTANCE_NONE→DEFAULT

  投递裁决（设计上绝不 hook）：
  NMS#enqueueNotification ─→ importance / PermissionHelper 检查 ─→ 丢弃
                             （clearCallingIdentity → system uid → 永不被欺骗）
```

## 🚀 快速开始

1. 构建（或下载 release APK）：

```
gradlew :app:assembleRelease
```

2. 安装并启用：

```
adb install app-release.apk
```

打开 **LSPosed → 模块 → DoNotComplain → 启用**。作用域固定为 `system`——确认勾选的是 `system` 条目（注入 `system_server` 的那个），**不是** `android` 包条目。

3. 重启。开机后模块日志应出现：

```
Installed N hooks in system_server
```

4. 验证效果：选一个已禁用通知的应用 → 打开它 → 不再出现"开启通知"的提示或横幅；它的通知依然被屏蔽；设置页里该应用的通知开关依然显示为关。

## ⚙️ 环境要求

- **框架**：LSPosed（或任何实现 libxposed API **102** 的框架），且具备系统进程 hook 能力（`PROP_CAP_SYSTEM`）
- **系统**：Android 8.1+（minSdk 27；现代框架生态仍支持的最低线）
- **Root**：框架本身需要
- **构建**：JDK 17+、Android SDK；Gradle wrapper 会处理其余

## 🧪 验证状态

对"验证过什么、没验证什么"的诚实交代：

| 项目                               | 状态        | 证据                                                                                                                            |
|------------------------------------|-------------|---------------------------------------------------------------------------------------------------------------------------------|
| Gradle 构建（debug + release，R8） | ✅ 已验证   | 两种变体构建干净；release APK 64 KB（dex 19 KB）                                                                                |
| 模块元数据打包                     | ✅ 已验证   | 两个 APK 内均确认 `META-INF/xposed/{java_init.list, module.prop, scope.list}`                                                   |
| 入口类经受 R8 混淆                 | ✅ 已验证   | 入口混淆为 `Lh;`（XposedModule 子类）；`java_init.list` 经 `-adaptresourcefilecontents` 自动改写（DFS 模式）                    |
| Hook 点签名                        | ✅ 源码核对 | 与 AOSP `android-8.1.0_r81`、`android-13.0.0_r1` 交叉核对（NMS、PermissionHelper、AppOpsService、客户端 `NotificationManager`） |
| 投递路径隔离                       | ✅ 源码核对 | 13+：`PermissionHelper.hasPermission` 运行于 `clearCallingIdentity()`；<13：AppOps 栈守卫覆盖内部 `checkOpNoThrow` 调用         |
| 真机行为                           | ⏳ 尚未测试 | 未做真机验证——见"项目状态"                                                                                                      |

## ⚠️ 项目状态

核心实现完成且通过构建验证，但**尚未进行真机测试**。hook 点是对 AOSP 源码核对的，不是对 OEM 分支——厂商 ROM 可能重命名或重塑内部服务（候选表探测能缓解但不能保证）。`system_server` 内的崩溃会把整个系统一起带崩，因此在新 ROM 上的首次开机请当作测试会话对待：做好通过 LSPosed（或 recovery）禁用模块的预案。

## 🚫 非商业声明

本项目由开发者出于个人兴趣发起，具有**非商业**性质：

- **永久免费**：没有任何付费功能、会员、订阅或内购，所有功能对所有用户完全开放。
- **无赞助渠道**：作者从未开设任何赞助渠道，也不接受任何形式的金钱捐赠——以保持项目的中立性。
- **非营利目的**：不涉及商业运营，作者不会从中获取任何直接或间接的经济利益。
- **研究与个人使用导向**：定位于个人设备定制与 Xposed 框架研究的工具，而非商业产品。任何商业使用均为使用者个人行为，与本项目无关。
- **禁止转售**：严禁转售或以营利为目的的再分发。请仅从本仓库（GitHub）获取。

## ⚖️ 免责声明

- **用途限制**：本项目仅供**个人设备定制、Xposed 开发研究与学习交流**使用——帮助用户屏蔽那些施压要求重新开启"用户已刻意关闭的通知"的应用。请勿用于任何非法用途。

- **后果警示**：欺骗权限检查**可能违反第三方应用的服务条款及你所在司法辖区的法律**。使用前请自行评估风险。开发者与贡献者**对由此产生的任何账号封禁、法律责任或其他后果概不负责**。

- **系统稳定性**：本模块运行于 `system_server` 内。模块缺陷（或与 OEM ROM 的交互问题）**可能引发需要 recovery 干预的启动循环**。请做好备份，谨慎测试新的 ROM 组合。

- **无担保**：本软件基于 GPL-3.0 提供，**不附带任何明示或默示的担保**，包括适销性、特定用途适用性与非侵权性。

- **兼容性免责**：不保证与所有 Android 版本、OEM ROM 或 Xposed 框架实现兼容。开发者对因系统更新或框架变更导致的问题概不负责。

- **责任限制**：在适用法律允许的最大范围内，**无论是否被告知可能性，作者或贡献者均不对因使用或无法使用本软件而产生的任何损害承担责任**。

- **用户责任**：使用者须自行承担使用本项目所产生的一切责任。

## 💬 联系方式

欢迎通过 GitHub Issues 提交问题、建议与 bug 报告。

## ⭐ 支持项目

如果这个模块帮你挡掉了一条"请开启通知"的横幅，欢迎在 GitHub 上点一个 ⭐。

你的支持能让更多人发现这个项目，也能让作者感受到持续维护的意义。

感谢你的认可。
