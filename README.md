# Link to Windows — OEM integration for Android 17

Native **Link to Windows / Phone Link** support for any Android 17 phone, done the way phone makers
(OnePlus, OPPO, Honor…) ship it: the phone screen and individual apps on the PC, PC mouse and
keyboard control, a black screen on the phone while you work from the PC, clipboard, audio and drag & drop.

Microsoft's apps are **never modified, re-signed or hooked**. This project only supplies the "OEM side"
that Microsoft's `deviceintegrationservice` expects to find on the phone. It was reverse-engineered
from the OnePlus 12 (OxygenOS 15) and re-implemented for Android 17.

There are two ways to use it. Both build from the **same Java/AIDL/C++ sources**:

| | **A. Root module** (`module/`) | **B. Custom ROM** (`rom/`) |
|---|---|---|
| For | Rooted **stock** Android 17 | People who build their own ROM (LineageOS 24 / Android 17) |
| How | One KernelSU/Magisk module (includes the LSPosed/Vector part) | `vendor/ltw` drop-in + `frameworks/base` patches, platform-signed |
| Install | Download the release, flash | `repo sync` one extra project, apply patches, build |
| Status | ✅ **Validated** on a Pixel 7a (lynx), stock Android 17 | ⚠️ Patches apply cleanly to `lineage-24.0`; **not yet built/booted** |

## Features

All of these were validated on a stock Android 17 Pixel 7a through the root module:

| Feature | Notes |
|---|---|
| Phone screen on the PC | No consent prompt each time; the broker grants MediaProjection |
| PC mouse / touch control | Injected through `cross_device_service` |
| PC keyboard typing | `vendor.virtual_keyboard` (uinput) — the on-screen keyboard stays hidden |
| **Apps**: each app in its own PC window | Several at once; from the app list, notifications, links (app-to-app), duplicate launch focuses the existing window |
| Move an app phone ⇄ PC | Opening a streamed app on the phone moves it back; landscape apps get landscape windows |
| Secure content | Reported to Windows (shown black there, as on OEM phones) |
| Black screen on the phone | Hidden from the PC mirror; **phone touches are blocked**, the PC keeps full control; swipe up or press power to dismiss; lock screen and fingerprint keep working |
| Clipboard sync | Both directions |
| App audio on the PC | e.g. YouTube in a PC window |
| Drag & drop | PC → phone app |
| Calls, messages, notifications, photos, recent apps | Link to Windows' own features |
| Files in Windows File Explorer | Link to Windows' own (`All files access`); enable it in Windows → Settings → Bluetooth & devices → Mobile devices |

Not supported: PC-side window resizing (Microsoft keeps it switched off, OnePlus too), OEM
Bluetooth transport (`feature_oem_rfcomm`). Instant hotspot is untested.

## What you need (both paths)

Two **Microsoft-signed** APKs. They are not included here; get them yourself and keep them unmodified.

| File name | Package | Where from | Tested |
|---|---|---|---|
| `DeviceIntegrationService.apk` | `com.microsoft.deviceintegrationservice` | Preinstalled on OnePlus/OPPO/realme/Honor phones (`/system_ext/priv-app/DeviceIntegrationService`), or an APK mirror ("Device Integration Service") | 2.24112, 2.25044 |
| `LinktoWindows.apk` | `com.microsoft.appmanager` | Any **single** (non-split) Microsoft-signed Link to Windows APK **not newer** than the one on Play, e.g. OnePlus' preinstalled `/my_stock/priv-app/LinktoWindows/LinktoWindows.apk` | 1.24112 as system copy, Play 1.26102 on top |

Why Link to Windows must be a system (privileged) app: on Android 17 a non-privileged MediaProjection owner
must run a `mediaProjection` foreground service, which Link to Windows doesn't, so mirroring would die. OEMs
preinstall it for that reason. Play then updates it as usual and it stays privileged.

Check the signature if you want: `apksigner verify --print-certs <apk>` should show a Microsoft certificate.

On the PC: Windows 10/11 with the **Phone Link** app, signed in to the same Microsoft account.

---

## A. Root module (stock Android 17)

### Requirements

- Android 17, rooted with **KernelSU** (plus a metamodule such as **Mountify** or **meta-overlayfs**) or
  **Magisk**.
- **LSPosed/Vector** with the modern libxposed API (100+).
- If you use **Hide My Applist**, exclude `com.microsoft.appmanager`,
  `com.microsoft.deviceintegrationservice` and `com.microsoftsdk.crossdeviceservicebroker` from hiding.

### Install

1. Download `ltw_oem_module-<version>.zip` from the [latest release](../../releases/latest). It includes
   LtwEmu, the LSPosed/Vector part, as a system app.
2. On the phone, create `/sdcard/Download/LinkToWindowsOEM/` and copy both Microsoft APKs into it,
   named exactly `DeviceIntegrationService.apk` and `LinktoWindows.apk`.
3. Remove conflicting copies:
   - uninstall any `deviceintegrationservice` you installed as a normal app:
     `adb shell pm uninstall com.microsoft.deviceintegrationservice`
     (only if it shows under `/data/app` in `adb shell pm path com.microsoft.deviceintegrationservice`);
   - uninstall any other `CrossDeviceServiceBroker` / Samsung-port modules. Exactly one broker may exist.
   - **Keep** Link to Windows from Play installed. It becomes an update of the system copy.
4. Install `ltw_oem_module-<version>.zip` in the KernelSU/Magisk manager. The installer copies the two APKs
   from step 2 into the module and aborts with a clear message if one is missing.
5. **Reboot.**
6. Enable **LtwEmu** in LSPosed/Vector with scope **System Framework** and
   **com.microsoft.deviceintegrationservice**, then **reboot again**. With Vector (`/data/adb/lspd/cli`
   present) the module does this itself on the first boot; you only reboot again. Manual equivalent:
   ```sh
   su -c "/data/adb/lspd/cli modules enable me.ayra.ltw.emu"
   su -c "/data/adb/lspd/cli scope set me.ayra.ltw.emu system/0 com.microsoft.deviceintegrationservice/0"
   ```
7. Open Link to Windows, sign in, pair with Phone Link on the PC, and allow the permissions it asks for.
8. On the PC open Phone Link → **Apps** / **Open phone screen**. The first time, accept the prompt on the phone.

### Check that it is active

Tap the module's **Action** button in KernelSU/Magisk. It checks every piece (priv-apps, broker,
`cross_device_service`, LtwEmu, keyboard daemon, SELinux domain) and says what to fix. By hand:

```sh
adb shell su -c "service check cross_device_service"     # must say: found   (run as root)
adb shell pm path com.microsoft.deviceintegrationservice  # /system/priv-app/...
adb shell pm list libraries | grep device-private        # library:com.microsoft.device-private
adb logcat -s LtwEmu CrossDeviceService LtwBlackScreen
```

`LtwEmu` should log `system_server install ... serviceHook=true permHooks=true`.

### If the phone doesn't boot

Android enforces a privileged-permission allowlist at boot. A Microsoft app version that requests a new
privileged permission can stop the boot. Boot to **KernelSU safe mode** (hold volume down during boot)
or Magisk safe mode, then remove the module. Report the DIS/Link to Windows versions in an issue.

### Update / uninstall

- Update: KernelSU/Magisk show module updates (`updateJson`); or install the new zip over the old one. The
  installer reuses the Microsoft APKs from the installed module, unless newer ones are in
  `/sdcard/Download/LinkToWindowsOEM/`. Reboot.
- Uninstall: remove the module in the manager, reboot. (If you ever installed `LtwEmu` as a normal app,
  uninstall that too.)

### Build it yourself

Requirements: JDK 21, Android SDK (platform 36), Android NDK r25+, bash, Python 3.

```sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :device-private:deviceprivateJar :broker:assembleRelease :ltwemu:assembleRelease
module/virtual_keyboard/build.sh
module/build-module.sh                      # zip without Microsoft APKs (like the release)
# or bundle them:  module/build-module.sh DeviceIntegrationService.apk LinktoWindows.apk
```

Output: `module/build/ltw_oem_module-*.zip` (LtwEmu included). Without signing variables the APKs use
your Android debug key (see [Releases](#releases-maintainers)).

What each piece does is described in [`module/README.md`](module/README.md).

---

## B. Custom ROM (LineageOS 24 / Android 17)

The ROM path is what a phone maker would ship: `cross_device_service` inside system_server, a platform-signed
broker, Microsoft's apps as privileged system apps. No root, no Xposed.

> ⚠️ The patches apply cleanly to LineageOS `lineage-24.0` (`frameworks/base` @ `d0a4045e`) and the code
> compiles against the Android 17 SDK, but **no full ROM has been built and booted yet**. The same code
> runs on device through the root module. Please report build results.

### Requirements

A LineageOS 24 build environment for your device: Linux x86_64 (or WSL2), about 300 GB disk, 32 GB+ RAM, and
the [LineageOS build guide](https://wiki.lineageos.org/build_guides) for your device. Your own release keys:
the broker is signed with **your** platform certificate.

### 1. Pull this repo into the tree

```sh
mkdir -p .repo/local_manifests
curl -L -o .repo/local_manifests/ltw.xml \
  https://raw.githubusercontent.com/FuSan21/LinkToWindows-OEM/main/rom/local_manifest.xml
repo sync -c vendor/ltw
```

This puts the repo at `vendor/ltw`; the ROM part is `vendor/ltw/rom`. Prefer a pinned version? Change
`revision="main"` in `ltw.xml` to a release tag.

### 2. Add the Microsoft APKs

```sh
cp /path/to/DeviceIntegrationService.apk vendor/ltw/rom/prebuilt/DeviceIntegrationService/
cp /path/to/LinktoWindows.apk            vendor/ltw/rom/prebuilt/LinktoWindows/
```

(`*.apk` is git-ignored, so they never get committed. Without them the build stops with a message saying
which one is missing.)

### 3. Apply the framework patches

```sh
vendor/ltw/rom/apply-patches.sh --check   # dry run
vendor/ltw/rom/apply-patches.sh           # git am onto frameworks/base
```

There are three patches, one per feature (core, black screen, Apps), carrying only the call sites. The
code lives in `vendor/ltw/rom/services`. The script skips patches that are already applied. **Run it
again after every `repo sync`**, since a sync can reset `frameworks/base`. CI checks every week that they
still apply to the latest `lineage-24.0`.

### 4. Hook it into the build

```sh
mkdir -p vendor/extra
echo '$(call inherit-product, vendor/ltw/rom/ltw.mk)' >> vendor/extra/product.mk
echo 'include vendor/ltw/rom/BoardConfigLtw.mk'       >> vendor/extra/BoardConfigExtra.mk
```

`vendor/extra/product.mk` is LineageOS' official extra-product hook. If your ROM has no
`BoardConfigExtra.mk` hook, add the `include` line to your device's `BoardConfig.mk` instead.

### 5. Build, sign, flash

```sh
source build/envsetup.sh
breakfast <device>
m target-files-package otatools          # then sign with YOUR keys:
# https://wiki.lineageos.org/signing_builds
```

Flash as usual. Then install/update Link to Windows from Play, pair it, and open the phone from Phone Link.

### Check on the device

```sh
adb shell service check cross_device_service           # found
adb shell pm list libraries | grep device-private       # library:com.microsoft.device-private
adb shell dumpsys package com.microsoftsdk.crossdeviceservicebroker | grep -E "MANAGE_MEDIA_PROJECTION|MONITOR_INPUT"
adb logcat -s CrossDeviceService CrossDeviceServiceBroker LtwBlackScreen
```

Things to look at on the first boot of a real ROM build (unverified assumptions):
the broker gets `deviceintegration.permission.ACCESS_DEVICE_INTEGRATION` through its platform signature (the
consent screen needs it); the virtual-keyboard sepolicy (`priv_app` vs. Android 17's `priv_app_<sdk>`).

Other Android 17 ROMs: `rom/` is plain Soong/Make plus `frameworks/base` patches, so it should port with
little effort. The patches touch `SystemServer`, `PhoneWindowManager`, `WindowState`, `DisplayPolicy`,
`InputMonitor`, `ActivityTaskManagerService`, `PendingIntentRecord` and `BackgroundActivityStartController`.
File-by-file reference: [`rom/README.md`](rom/README.md).

---

## How it works (short)

- **`cross_device_service`** (system_server, `rom/services/java/.../CrossDeviceService.java`):
  the OEM binder that deviceintegrationservice talks to (`android.app.ICrossDeviceService`, transaction codes
  identical to OnePlus). It handles input injection, wake, thermal readings, task-change events and
  "Apps" (Remote Task).
- **`android.app.CrossDeviceManager`** lives in the shared library `com.microsoft.device-private`,
  loaded into deviceintegrationservice's own ClassLoader. That keeps it out of `framework.jar`, so none of
  its methods counts as a hidden API.
- **Broker** `com.microsoftsdk.crossdeviceservicebroker` (`rom/broker`): grants MediaProjection
  without a prompt, handles clipboard and launches, and draws the **black screen**.
  deviceintegrationservice uses the broker's black screen ("V2") because the broker holds `MONITOR_INPUT`.
  Its gesture monitor steals touches from the phone's own touchscreen but leaves the PC's injected input
  alone.
- **"Apps"** (`LtwRemoteTaskManager`): each app launched from the PC gets its own trusted virtual
  display, which deviceintegrationservice encodes to its own PC window.
- **`vendor.virtual_keyboard`** (`rom/virtual_keyboard`): a uinput keyboard daemon for PC typing.
- The **root module** supplies the same pieces with files (priv-apps, permissions, sysconfig) plus
  LSPosed hooks in system_server at the same call sites the `frameworks/base` patches use, calling the
  **same classes** (`rom/services/java`).

The full reverse-engineering notes, the OnePlus reference and the test log are in
[`docs/OEM_INTEGRATION_NOTES.md`](docs/OEM_INTEGRATION_NOTES.md).

## Repository layout

```
rom/                          custom-ROM drop-in (cloned to vendor/ltw, used as vendor/ltw/rom)
  ltw.mk, BoardConfigLtw.mk   product + board hooks
  aidl/                       android.app.ICrossDeviceService & co (OnePlus-compatible codes)
  device-private/             com.microsoft.device-private (CrossDeviceManager)
  broker/                     CrossDeviceServiceBroker (platform-signed): projection, clipboard, black screen
  virtual_keyboard/           vendor.virtual_keyboard uinput daemon (C++)
  permissions/, sepolicy/     privapp allowlist, default grants, hidden-API sysconfig, SELinux
  prebuilt/                   put the two Microsoft APKs here
  services/                   our system_server classes, compiled into services.core (java/ also by module/)
  patches/frameworks_base/    0001 core, 0002 black screen, 0003 Apps: call sites only (apply-patches.sh)
  local_manifest.xml          repo local manifest
module/                       root-module path (Gradle + scripts)
  ltwemu/                     LSPosed/Vector module = the frameworks/base patches as hooks
  broker/, device-private/    build rom/broker and rom/device-private for stock Android
  stubs/                      compile-only stubs for hidden APIs
  template/                   KernelSU/Magisk module files (customize.sh, service.sh, sepolicy.rule, XMLs)
  virtual_keyboard/build.sh   NDK build of rom/virtual_keyboard
  build-module.sh             assembles the module zip
docs/                         reverse-engineering notes
```

## Releases (maintainers)

`.github/workflows/build.yml` runs shellcheck, checks that the patches apply to the latest
`lineage-24.0` (also weekly), and builds the module on every push. Pushing a tag `v*` publishes a GitHub
release with `ltw_oem_module-<tag>.zip`, `update.json` (KernelSU/Magisk in-app updates) and
`SHA256SUMS`. Microsoft APKs are never included. Add the release's changes to `CHANGELOG.md` first: the
module manager shows it.

So that updates install over each other, sign with a fixed key. Add these repository secrets:

| Secret | Value |
|---|---|
| `LTW_KEYSTORE_B64` | `base64 -w0 release.jks` |
| `LTW_KEYSTORE_PASSWORD` | keystore password |
| `LTW_KEY_ALIAS` | key alias |
| `LTW_KEY_PASSWORD` | key password |

Release: `git tag v1.0.0 && git push origin v1.0.0`. Without the secrets the build still works, but it is
debug-signed (with a warning).

## License

[MIT](LICENSE). Microsoft, Link to Windows and Phone Link are trademarks of Microsoft. This project contains
no Microsoft code or binaries and is not affiliated with Microsoft.
