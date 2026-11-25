# Changelog

## v1.1.0

Bluetooth side channel, used by Link to Windows for **Instant hotspot** and **Bluetooth wake**.

- New: the OEM Bluetooth transport (`feature_oem_rfcomm`). `cross_device_service` provides
  `IRfcommOemManager` (`LtwRfcommManager`, ROM and root module): system_server keeps Link to Windows'
  Bluetooth server listening while the apps sleep, across reboots and when Bluetooth is switched off and
  on, wakes Link to Windows when the PC connects and hands it the connection.
- The broker declares `deviceintegration.permission.BLUETOOTH_TRANSPORT_EVENT` (Link to Windows only
  receives the wake-up with it).
- deviceintegrationservice asks once for the *Nearby devices* (Bluetooth) permission when the feature is
  first used.
- Phone paired with the PC before updating: remove it in Windows → Settings → Bluetooth & devices and
  pair again, so Windows sees the new Bluetooth service.
- Builds: versionCode is the commit count again, so every release installs as an update; release notes
  come from this file.

## v1.0.0

First release: root module for stock Android 17 and LineageOS 24 drop-in. Phone screen, Apps, black
screen (phone touches blocked), PC keyboard, clipboard, audio, drag & drop.

- Root module: LtwEmu (the LSPosed/Vector part) is included in the module zip; with Vector it is enabled
  and scoped automatically. One zip to install.
- Root module: in-app updates in KernelSU/Magisk (`updateJson`); updates reuse the installed Microsoft APKs.
- Root module: **Action** button runs a health check.
- Root module: SELinux rules for the `priv_app_<n>` domains the platform's `seapp_contexts` defines
  (Android 17+ runs priv-apps in per-targetSdk domains).
- ROM: three feature patches (core, black screen, Apps) carrying only call sites; the code moved to
  `rom/services` and is shared with the root module (`LtwWindowPolicy`).
- ROM: clear build error when a Microsoft APK is missing.
- CI: shellcheck, weekly check that the patches apply to `lineage-24.0`, `SHA256SUMS`, pinned actions.
- Root module: Link to Windows no longer needs a separate APK: the installed Play version (base + split
  APKs) becomes the system copy. A missing Microsoft app never stops the install; the Action button
  finishes the setup (opens Play for Link to Windows) one step per tap.
- Root module: the Action button reboots by itself (10 s countdown) when a setup step needs it,
  including after Vector enabled LtwEmu; never twice for the same step.
- Root module: deviceintegrationservice is picked up straight from `/sdcard/Download` (APK-mirror file
  name); the Action button opens its download page, says exactly where to save it (directly in
  Download/, file name kept, with an example) and points out a copy in another folder or an APKM
  bundle. Every APK is verified with Android's signature
  verifier against Microsoft's pinned certificate before it becomes a system app.
- Root module: the installer refuses to install without module mounting (built into Magisk/APatch;
  a metamodule on KernelSU), Zygisk and Vector/LSPosed, instead of installing a module that does
  nothing; the Action button reports them.
- deviceintegrationservice no longer crash-loops while `cross_device_service` is missing (the boot
  right after install, or if LtwEmu fails to load): `CrossDeviceManager` behaves like a device without
  the feature.
- Root module: a module update no longer asks for an extra reboot when LtwEmu is already enabled.
- Root module: fixed a hard freeze after boot on a 4.14 kernel (Xiaomi Pad 5, KernelSU Next): the live
  SELinux patch named a domain that doesn't exist (`priv_app_<targetSdk>`), and the failed patch hung
  the kernel. Only domains from `seapp_contexts` are patched now.
- Root module: service.sh runs once per boot (KernelSU Next can start the service stage twice, which
  started two keyboard daemons).
- Docs: known problems on 4.x kernels (Xiaomi Pad 5): meta-overlayfs shutdown panic.
