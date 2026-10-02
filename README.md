# DoNotComplain

[简体中文](README_ZH.md) | English | [AI collaboration doc](README_AGENT.md)

## 📖 Introduction

**DoNotComplain** is an LSPosed module (modern `libxposed` API 102) that makes apps believe their notification permission is **enabled** — so when you have deliberately disabled their notifications, they stop nagging: no more permission prompts, no more in-app banners begging you to "turn on notifications".

The module hooks **only inside `system_server`** (scope is fixed to the virtual `system` package). Not a single byte of module code is injected into any app process. Every legitimate path an app can take to ask "do I have notification permission?" converges on a handful of query methods in the system server — `NotificationManagerService`, `AppOpsService`, `PermissionManagerService`. The module answers those queries with `true` / `MODE_ALLOWED` / `PERMISSION_GRANTED`, **but only when the caller is asking about itself**. The actual delivery verdict is untouched: notifications you disabled stay disabled, exactly as you configured.

The name is the whole design: apps should not complain about a decision their user already made.

> **Using an AI assistant (Copilot / Claude / GPT ...) for development on this project?** Read [README_AGENT.md](README_AGENT.md) first — it is written specifically for AI and contains the project's **safety invariants and hard red lines** (query-path-only hooking, self-query-only lying, the delivery-path isolation contracts). Skipping it and editing code directly is very likely to break the module's core safety property or crash `system_server`.

## ✨ Features

- **System-server-only injection**
  The module's scope is statically `system` (`staticScope=true`, `scope.list=system`). No app process is ever touched — nothing for an in-app integrity check or `/proc/self/maps` scan to find. This is also why it generalizes: one set of hooks covers *every* app on the device, present and future.

- **Four-layer query coverage** (signatures verified against AOSP 8.1 / 13 sources)
  - `NotificationManagerService#areNotificationsEnabledForPackage` / `#areNotificationsEnabledForChannel` / `#areChannelsEnabled` — the endpoint of `NotificationManager.areNotificationsEnabled()` and `NotificationManagerCompat.areNotificationsEnabled()` (which nearly every app and push SDK uses);
  - `AppOpsService#checkOperation` — for apps probing `OP_POST_NOTIFICATION` through `AppOpsManager` directly;
  - `PermissionManagerService#checkPermission` / `#checkUidPermission` (Android 13+) — the runtime-permission path that `POST_NOTIFICATIONS` queries take;
  - `NotificationManagerService#getNotificationChannel(s)` — per-channel states: channels the user turned off are reported as `IMPORTANCE_DEFAULT` instead of `IMPORTANCE_NONE`.

- **Only the query is lied to — never the verdict**
  The delivery path (`NMS#enqueueNotification` → importance check → drop) shares *no* code with the hooked query methods. On Android 13+, the delivery-side check even runs under `clearCallingIdentity()` (calling uid = system), so it is structurally isolated from the self-query lie. **Apps believe notifications are on; the system continues to enforce your off switch.**

- **Only self-queries are deceived**
  Every hook fires only when the binder calling uid matches the uid being queried (or the queried package belongs to that uid). Settings, SystemUI, and any system component querying *other* apps always receive the real state — your notification settings page never lies to you.

- **AppOps delivery-path guard**
  On Android < 13, the delivery path internally calls `checkOpNoThrow` *as the app*. The AppOps hook inspects the call stack and lets any call coming through `com.android.server.*` frames pass through with the true result — the lie never leaks into the verdict.

- **No in-place mutation of system objects**
  `getNotificationChannel` may return the very object `NMS` stores its state in. The module clones channels via `Parcel` round-trip and rewrites the copy — the system's persisted channel state is never modified.

- **Zero UI, zero configuration** (modeled after [DisableFlagSecure](https://github.com/LSPosed/DisableFlagSecure))
  The module ships no activities at all. The LSPosed enable switch *is* the switch: enable = deceive every app's self-queries globally; disable = full truth restored. Nothing to configure, nothing to launch, ~60 KB release APK with a single `compileOnly` dependency.

- **Hot reload support**
  `autoHotReload=true`: the module updates itself inside `system_server` without rebooting the device. Hooks are registered with stable ids (`Executable.toGenericString()`); a reload atomically replaces same-id hooks and unhooks stale ones, with the system `ClassLoader` carried across generations via saved state.

- **OEM-tolerant symbol discovery**
  `AppOpsService` has moved packages three times across Android versions (8.x → 9/10 → 11+); the module probes a candidate list, matches binder methods by name + parameter shape (not exact signatures), and treats every hook point as independent — one missing symbol disables that point only.

## 📐 How the deception works

```
  app process (NEVER injected)            system_server (the only injection point)
 ─────────────────────────────────────────────────────────────────────────────
  NotificationManager
    .areNotificationsEnabled()    ─binder→  NMS#areNotificationsEnabledForPackage
  NotificationManagerCompat                     │ self-query?  → true   (the lie)
    .areNotificationsEnabled()    ─ same →     │ other uid?   → proceed (truth)
  AppOpsManager
    .checkOpNoThrow(OP_POST_NOT.) ─binder→  AppOpsService#checkOperation
                                               │ self-query && no server frames
  Android 13+ runtime permission    ─binder→    │   → MODE_ALLOWED
    check of POST_NOTIFICATIONS              PermissionManagerService#check*
                                               │ else → proceed (truth)
  per-channel state               ─binder→  NMS#getNotificationChannel(s)
                                               → clone, IMPORTANCE_NONE→DEFAULT

  delivery verdict (NOT hooked, by design):
  NMS#enqueueNotification ─→ importance / PermissionHelper check ─→ dropped
                             (clearCallingIdentity → system uid → never lied to)
```

## 🚀 Quick Start

1. Build (or grab a release APK):

```
gradlew :app:assembleRelease
```

2. Install and enable:

```
adb install app-release.apk
```

Open **LSPosed → Modules → DoNotComplain → Enable**. The scope is fixed to `system` — make sure the `system` entry (the one that injects into `system_server`) is checked. **Not** the `android` package entry.

3. Reboot. After boot, the module log should show:

```
Installed N hooks in system_server
```

4. Verify the effect: pick an app whose notifications you have disabled → open it → no more "enable notifications" prompts or banners. Its notifications remain blocked. The Settings page still shows the toggle as off.

## ⚙️ Requirements

- **Framework**: LSPosed (or any framework implementing libxposed API **102**) with system-process hooking capability (`PROP_CAP_SYSTEM`)
- **OS**: Android 8.1+ (minSdk 27; the oldest line still supported by the modern framework ecosystem)
- **Root**: required by the framework itself
- **Build**: JDK 17+; Android SDK; Gradle wrapper handles the rest

## 🧪 Verification Status

Honest accounting of what has and has not been verified:

| Item                               | Status            | Evidence                                                                                                                                     |
|------------------------------------|-------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| Gradle build (debug + release, R8) | ✅ verified       | Both variants build clean; release APK is 64 KB with a 19 KB dex                                                                             |
| APK packaging of module metadata   | ✅ verified       | `META-INF/xposed/{java_init.list, module.prop, scope.list}` confirmed inside both APKs                                                       |
| Entry class survives R8            | ✅ verified       | Entry obfuscated to `Lh;` extending `XposedModule`; `java_init.list` auto-rewritten by `-adaptresourcefilecontents` (DFS pattern)            |
| Hook-point signatures              | ✅ source-audited | Cross-checked against AOSP `android-8.1.0_r81` and `android-13.0.0_r1` (NMS, PermissionHelper, AppOpsService, client `NotificationManager`)  |
| Delivery-path isolation            | ✅ source-audited | 13+: `PermissionHelper.hasPermission` runs under `clearCallingIdentity()`; <13: AppOps stack guard covers the internal `checkOpNoThrow` call |
| On-device behavior                 | ⏳ not yet tested | No real-device pass has been performed — see Project Status                                                                                  |

## ⚠️ Project Status

Core implementation is complete and build-verified, but **no real-device test has been performed yet**. The hook points are audited against AOSP sources, not against OEM forks — vendor ROMs may rename or reshape internal services (the candidate-list probing mitigates, but cannot guarantee, this). A crash inside `system_server` takes the whole system down with it, so treat the first boots on a new ROM as a test session: be prepared to disable the module via LSPosed (or recovery) if boot loops occur.

## 🚫 Non-Commercial Statement

This project is initiated by the developer out of personal interest and is **non-commercial** in nature:

- **Permanently Free**: no paid features, memberships, subscriptions, or in-app purchases. All features are fully accessible to all users.
- **No Sponsorship Channels**: the author has never opened sponsorship channels and accepts no financial donations — to keep the project neutral.
- **Non-Profit Purpose**: no commercial operation; the author derives no direct or indirect financial benefit.
- **Research & Personal-Use Oriented**: positioned as a tool for personal device customization and Xposed-framework research, not a commercial product. Any commercial use is the user's own initiative and unrelated to this project.
- **Resale Prohibited**: resale or profit-oriented redistribution is strictly prohibited. Obtain the module only from this repository (GitHub).

## ⚖️ Disclaimer

- **Purpose Limitation**: this module is intended for **personal device customization, Xposed development research, and educational purposes** — letting users silence apps that pressure them into re-enabling notifications the user deliberately turned off. Do not use it for any illegal purpose.

- **Consequences Warning**: spoofing permission checks **may violate the terms of service of third-party apps** and the laws of your jurisdiction. Assess the risks yourself. The developer and contributors are **not responsible for any account bans, legal liabilities, or other consequences** arising from such use.

- **System Stability**: the module runs inside `system_server`. A defect in it (or in its interaction with an OEM ROM) **may cause boot loops requiring recovery intervention**. Keep backups; test new ROM combinations cautiously.

- **No Warranty**: this software is provided under GPL-3.0, **without any express or implied warranties**, including merchantability, fitness for a particular purpose, and non-infringement.

- **Compatibility Disclaimer**: no guarantee of compatibility with all Android versions, OEM ROMs, or Xposed framework implementations. The developer assumes no responsibility for issues caused by system updates or framework changes.

- **Limitation of Liability**: to the fullest extent permitted by applicable law, **in no event shall the author or contributors be liable** for any damages arising from the use or inability to use this software.

- **User Responsibility**: users assume all responsibilities arising from the use of this project.

## 💬 Contact

Issues, suggestions, and bug reports are welcome via GitHub Issues.

## ⭐ Support the Project

If this module saves you from one more "please enable notifications" banner, consider giving it a ⭐ on GitHub.

Your support helps more people discover the project, and tells the author the maintenance is worth it.

Thank you.
