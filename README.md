# DoNotComplain

## 它是做什么的 | What it does

**DoNotComplain** 是一个 LSPosed 模块：当你关闭某个应用的通知后，它会阻止该应用察觉——"开启通知"的弹窗、横幅、红点从此消失。它**不会**重新打开通知：通知依然关闭、不会送达，只是应用不再为此骚扰你。

**DoNotComplain** is an LSPosed module: after you disable an app's notifications, the app can no longer tell — "enable notifications" popups, banners and badges vanish. It does **not** re-enable them: notifications stay off and undelivered; the app simply stops pestering you.

## 效果 | Effects

- ✅ 应用内"开启通知"引导弹窗、横幅消失
- ✅ 系统设置里通知开关依然显示为**关**（真实状态不变）
- ✅ 通知确实不会送达
- ✅ 覆盖**所有**应用，无需配置；禁用模块并重启即恢复原状

- ✅ In-app "enable notifications" prompts and banners disappear
- ✅ The system Settings page still shows the toggle as **off** (real state unchanged)
- ✅ Notifications genuinely remain blocked
- ✅ Covers **all** apps with zero configuration; disable + reboot restores everything

## 已验证 | Verified

真机实测：OPPO PGX110 / ColorOS 15（Android 15）、OPPO PLR110 / ColorOS 16（Android 16）——多款主流应用的引导全部消失，系统通知开关与投递行为不受影响。理论上支持 Android 8.1+；其他 ROM 未经充分实测，反馈请附 LSPosed 日志。

Tested on OPPO PGX110 / ColorOS 15 (Android 15) and OPPO PLR110 / ColorOS 16 (Android 16): prompts in multiple mainstream apps vanished; real toggles and delivery were unaffected. Android 8.1+ in theory; other ROMs not extensively tested — attach the LSPosed log when reporting issues.

## 使用方法 | How to use

前提：已 root 的设备 + [LSPosed](https://github.com/LSPosed/LSPosed)（或兼容 libxposed API 102 的框架）。

1. 安装 APK
2. **LSPosed → 模块 → DoNotComplain → 启用**（作用域已内置固定，无需勾选应用；界面勾选被自动还原属正常）
3. 重启手机
4. 打开一个你关过通知的应用——它不再提示

验证生效：LSPosed 日志出现 `Installed N hooks in system_server`（N > 0）。

Prerequisites: a rooted device with [LSPosed](https://github.com/LSPosed/LSPosed) (or any framework compatible with libxposed API 102).

1. Install the APK
2. **LSPosed → Modules → DoNotComplain → Enable** (scope is built-in and fixed; UI ticks get auto-reverted, which is expected)
3. Reboot
4. Open an app whose notifications you disabled — it no longer complains

To confirm: the LSPosed log shows `Installed N hooks in system_server` (N > 0).

## 边界 | Boundaries

管不了：应用自有设置页里的推送开关（去应用内打开即可，非本模块对抗目标）、系统权限申请对话框、服务端下发的运营弹窗。

Not covered: the app's own in-app push toggle (enable it in the app's settings — not this module's adversary), system permission-request dialogs, server-driven promotional popups.

## 风险与免责 | Risks & Disclaimer

模块运行于系统核心进程 system_server，**理论上**缺陷或与非常规 ROM 的冲突可能导致 boot loop（可经 recovery 卸载恢复），请做好备份。本项目仅供个人设备定制与 Xposed 研究学习；使用本软件欺骗权限检查可能违反第三方应用服务条款，账号封禁等后果自负；软件按 GPL-3.0 提供，不附任何担保，作者不对任何损失承担责任。

This module runs inside `system_server`: **in theory** a defect or unusual-ROM conflict could cause a boot loop (recoverable by uninstalling via recovery) — keep backups. For personal device customization and Xposed research only; spoofing permission checks may violate third-party apps' terms of service — consequences such as account bans are on you. Provided under GPL-3.0 with no warranties; the author is liable for nothing.

## 非商业声明 | Non-Commercial

本项目永久免费、无赞助渠道、不接受捐赠。许可仅为 GPL-3.0，**不设商业例外**，不接受双许可洽谈；再分发须完整遵守 GPL 并保留本声明与署名；请仅从本仓库或官方 Releases 获取，非官方来源风险自负。

Permanently free, no sponsorship, no donations. GPL-3.0 only — **no commercial exceptions**, no dual licensing. Redistribution must comply with GPL in full and keep this statement intact; obtain builds only from this repo or its official Releases.

## 反馈与支持 | Feedback & Support

问题与建议欢迎通过 GitHub Issues 提交，ROM 兼容性问题请附 LSPosed 日志。二次开发（尤其借助 AI）请先读 [README_AGENT.md](README_AGENT.md)。觉得有用就点个 ⭐。

Issues and suggestions via GitHub Issues; attach the LSPosed log for ROM compatibility reports. Contributors (especially AI-assisted) should read [README_AGENT.md](README_AGENT.md) first. If it helps, give it a ⭐.
