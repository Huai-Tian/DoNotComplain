# Security Policy

## Reporting a Vulnerability

**Report privately — do NOT open a public issue.**

- **Preferred**: GitHub Private Vulnerability Reporting
  (Security tab → "Report a vulnerability")
- **Also accepted**: encrypted email to huaitian.behinder@gmail.com,
  GPG key `125F 7101 1717 0318 A318  F9C4 7FA3 DE0E 6B42 E01A`

Include: module version, device model, Android version, ROM,
and the LSPosed log (captured when the issue reproduces).

## Scope

The hook logic running inside `system_server`, its interaction
with OEM ROMs, and anything that could affect boot stability or
let an app bypass the module's spoofing.

Out of scope: the "honest boundaries" documented in the README
(in-app push toggles, system permission dialogs, server-driven
popups) — those are design boundaries, not vulnerabilities.

## What to expect

Non-commercial personal project, maintained best-effort:
**no guaranteed response time**. Confirmed issues will be fixed
and announced via GitHub Security Advisories.

## Safe harbor

Good-faith research reported privately will not face legal action.