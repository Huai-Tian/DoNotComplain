# DoNotComplain

[简体中文](README_ZH.md) | English | [AI collaboration doc](README_AGENT.md)

## What it does

**DoNotComplain** is an LSPosed module. When you turn off an app's notifications, this module stops the app from noticing — so the "turn on notifications, don't miss out" popups, banners, and nag badges disappear.

It does **not** re-enable your notifications. They stay off, nothing gets delivered — the app just stops pestering you about it.

## Effects

- ✅ In-app "enable notifications" prompts and banners disappear
- ✅ The system Settings page still shows the app's notification toggle as **off** (real state unchanged)
- ✅ Notifications genuinely remain blocked
- ✅ Covers **all** apps on the device — nothing to configure per app
- ✅ Disable the module in LSPosed and reboot — everything returns to normal

## Verified

Tested on a real device: OPPO PGX110 / ColorOS 15 (Android 15).

"Enable notifications" prompts in multiple mainstream apps all disappeared; the real notification toggles and delivery behavior were unaffected.

Android 8.1+ should work in theory; ROMs other than ColorOS have not been extensively tested — when reporting issues, please attach the LSPosed log.

## How to use

Prerequisites: a rooted device with [LSPosed](https://github.com/LSPosed/LSPosed) (or any framework compatible with libxposed API 102).

1. Install the APK
2. Open **LSPosed → Modules → DoNotComplain → Enable**
   (the scope is built-in and fixed — you don't need to tick any app; ticks made in the LSPosed UI will be automatically reverted, which is expected)
3. Reboot the device
4. Open an app whose notifications you have disabled — it no longer complains

To confirm it's active: the LSPosed log shows `Installed N hooks in system_server` (N > 0).

## What it can NOT do (honest boundaries)

- **The app's own in-app push toggle**: some apps have a separate "in-app messaging" switch, and their banner reminds you about *that* switch, not the system permission — just enable it in the app's own settings. That is not this module's adversary
- **System permission-request dialogs**: the standard Android dialog shown when an app actively requests a permission is system UI and is unaffected
- **Server-driven promotional popups**: marketing content pushed from the network, unrelated to the local permission state

## Risks

- The module runs inside the system's core process (system_server). **In theory**, a module defect or a conflict with an unusual ROM could cause a boot loop — recoverable by uninstalling the module via recovery. No such issue occurred on verified devices, but be aware of the risk and keep backups

## ⚖️ Disclaimer

- **Purpose Limitation**:
  This project is intended for **personal device customization, Xposed development research, and educational purposes** only — helping users silence apps that pressure them into re-enabling notifications the user deliberately turned off.
  Do not use this project for any illegal purpose.

- **Consequences Warning**:
  Spoofing permission checks with this software **may violate the terms of service of third-party applications**, and may result in account suspension, device restrictions, or other losses.
  You should assess the risks before using it. The developer and contributors **are not responsible for any account bans, legal liabilities, or other consequences** arising from such use.

- **System Stability**:
  This module runs inside `system_server`. A defect in it (or in its interaction with an OEM ROM) **may cause boot loops requiring recovery intervention**. Keep backups; test new ROM combinations cautiously.

- **No Warranty**:
  This software is provided under the terms of its license (GPL-3.0), **without any express or implied warranties**, including but not limited to the warranties of merchantability, fitness for a particular purpose, and non-infringement.

- **Compatibility Disclaimer**:
  This software **does not guarantee full compatibility with all Android versions, OEM ROMs, or Xposed framework implementations**. The developer assumes no responsibility for functional issues or losses caused by system updates, framework changes, or other uncontrollable factors.

- **Limitation of Liability**:
  To the fullest extent permitted by applicable law, **in no event shall the author or contributors be liable** for any direct, indirect, incidental, special, or consequential damages arising out of or in connection with the use or inability to use this software, even if advised of the possibility of such damages.

- **User Responsibility**:
  Users assume all legal responsibilities arising from the use of this project.

- **Final Interpretation**:
  The final interpretation of this disclaimer belongs to the author of this project.

## 🚫 Non-Commercial Statement

This project was started by the developer out of personal interest and is **non-commercial** in nature:

- **Permanently free**: no paid features, memberships, subscriptions, or in-app purchases
- **No sponsorship channels**: the author has never opened sponsorship channels and accepts no donations of any kind
- **Research & personal-use oriented**: positioned as a tool for personal device customization and Xposed framework research, not a commercial product
- **Resale prohibited**: resale or profit-oriented redistribution is strictly prohibited; obtain the module only from this repository (GitHub)

## 💬 Feedback

Issues, suggestions, and bug reports are welcome via GitHub Issues. When reporting ROM compatibility problems, please attach the LSPosed log.

If you are contributing to this project (especially with AI assistance), read [README_AGENT.md](README_AGENT.md) first — it contains the project's safety red lines and constraints.

## ⭐ Support the Project

If this module saved you from one more "please enable notifications" banner, consider giving it a ⭐ on GitHub.

Your support helps more people discover the project, and tells the author the maintenance is worth it.

Thank you.
