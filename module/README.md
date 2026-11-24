# `module/` — root module for stock Android 17

Install steps are in the [main README](../README.md#a-root-module-stock-android-17). This file explains how
the module recreates the OEM environment on a stock ROM, and what we learned on the way.

Nothing here hooks Microsoft's apps. The module supplies the same pieces the ROM build has, using files and
a few system_server hooks.

## Pieces

| ROM build (`rom/`) | Root module | Built from |
|---|---|---|
| `com.microsoft.device-private` shared library | `/system/framework` jar + XML (overlay) | `device-private/` ← `rom/device-private`, `rom/aidl` |
| broker, platform-signed `/system_ext/app` | `/system/priv-app`, own key; **defines** `MANAGE_CROSS_DEVICE` | `broker/` ← `rom/broker` |
| deviceintegrationservice priv-app | `/system/priv-app/DeviceIntegrationService` | your APK (copied by `customize.sh`) |
| Link to Windows priv-app | `/system/priv-app/LinktoWindows`; Play version installs as its update | your APK |
| privapp allowlist / hidden-API sysconfig / default grants | same XMLs in `/system/etc` | `template/system/etc` |
| `cross_device_service` in SystemServer (patch 0001) | `ltwemu` hook after `ActivityManagerService.systemReady`, **same `CrossDeviceService` source** | `ltwemu/` + `rom/services/java` |
| `getSystemService("cross_device_service")` fetcher (0001) | `ltwemu` hook on `SystemServiceRegistry.getSystemService` in deviceintegrationservice | `ltwemu/` |
| broker's platform signature | `ltwemu` grants the broker **only** `MANAGE_MEDIA_PROJECTION`, `READ_CLIPBOARD_IN_BACKGROUND`, `ACCESS_DEVICE_INTEGRATION`, `MONITOR_INPUT`, `USE_BIOMETRIC_INTERNAL` | `ltwemu/` |
| window types 2098/2099 (0002) | hook on `PhoneWindowManager.checkAddPermission` → **same `LtwWindowPolicy`** (deviceintegrationservice + broker) | `ltwemu/` + `rom/services/java` |
| black screen off the mirror, not touchable, trusted (0002) | hooks on `WindowState.setClientSurface`, `DisplayPolicy.adjustWindowParamsLw`, `InputMonitor.isTrustedOverlay` | `ltwemu/` |
| power key dismisses the black screen (0002) | hook on `PhoneWindowManager.interceptKeyBeforeQueueing` | `ltwemu/` |
| Apps / Remote Task (0003) | hook on `ActivityTaskManagerService.startActivityAsUser` → **same `LtwRemoteTaskManager` source** | `ltwemu/` + `rom/services/java` |
| Apps: notifications, background-start allowlist, phone switch (0003) | hooks on `PendingIntentRecord.sendInner` and `BackgroundActivityStartController.checkBackgroundActivityStartAllowedByRealCaller` (+ `deoptimize` of its caller) | `ltwemu/` |
| `cross_device_service` sepolicy type | `sepolicy.rule`: `default_android_service` add/find (incl. `priv_app_36`) | `template/` |
| `vendor.virtual_keyboard` init service | same binary (NDK build), started by `service.sh` as root with deviceintegrationservice's UID | `virtual_keyboard/build.sh` ← `rom/virtual_keyboard` |

## Module files

| File | When | What |
|---|---|---|
| `ltw_apks.sh` | — | Finds each Microsoft APK: the copy installed as a normal app (base + split APKs), the previous module install, `/sdcard/Download/LinkToWindowsOEM/<Name>.apk`, or a download in `/sdcard/Download` (any name containing "device integration service"). Each new APK is checked by `ApkCheck` (in LtwEmu, run with `app_process`): Android's signature verifier, Microsoft's certificate SHA-256 pinned, package name |
| `customize.sh` | install | Places the Microsoft APKs it finds; never aborts on a missing one |
| `service.sh` | every boot | SELinux rule for deviceintegrationservice's `priv_app_<targetSdk>`; enables LtwEmu in Vector once; runs `vendor.virtual_keyboard` |
| `action.sh` | Action button | Finishes the setup one step per tap (copies a now-available APK, opens Play for Link to Windows, asks for a reboot), then the health check |
| `system/app/LtwEmu` | — | The LSPosed/Vector module, shipped as a system app |
| `module.prop` `updateJson` | — | In-app updates from the latest GitHub release |

## Black screen

deviceintegrationservice has two black-screen modes. If the broker holds `MONITOR_INPUT`, it calls the
broker's `showBlackScreen` / `hideBlackScreen` ("V2"). Otherwise it draws its own window 2099 and tags PC
input with flag `0x1000`, which OnePlus' native input stack presumably honours. Stock Android doesn't, so
we use V2. The broker (`rom/broker/.../BlackScreen.java`):

- draws a type-2099 window with deviceintegrationservice's own title, description and icon. It stays off the
  PC mirror and doesn't block injected PC input;
- runs a gesture monitor that **steals every touch from the phone's own touchscreen**. Injected PC events
  (virtual device id `-1`) pass untouched;
- copies deviceintegrationservice's UX: content fades after 60 s, a touch brings it back, a vertical swipe
  over 150 px dismisses it. The power key dismisses it too;
- never blocks while the **keyguard is locked**, and leaves the **under-display fingerprint** area alone
  while a fingerprint authentication runs (BiometricPrompt in apps);
- known limit: a phone touch during a PC drag cancels the PC drag too.

## Lessons (each was a real failure on device)

1. **SELinux:** Android 17 runs priv-apps as `priv_app_<targetSdk>` (`priv_app_36`), not `priv_app`.
2. **Consent screen:** deviceintegrationservice's `MediaProjectionPermissionActivity` needs
   `ACCESS_DEVICE_INTEGRATION`, and the broker is what launches it.
3. **Link to Windows must be a priv-app.** A non-privileged MediaProjection owner must run a `mediaProjection`
   foreground service on Android 17. Link to Windows' service is `connectedDevice`, so the session dies
   silently.
4. **Window type 2099:** AOSP rejects it, and the `BadTokenException` crashes deviceintegrationservice.
5. **`vendor.virtual_keyboard`** is required for PC typing (deviceintegrationservice waits forever otherwise).
   A real uinput keyboard (`KEYBOARD|ALPHAKEY`) keeps the soft keyboard hidden. Leave out the `BTN_*` ranges, or
   Android also classes it as a gamepad/stylus.
6. **Android 17 window surfaces are client-created** (`WindowState.setClientSurface`), so skip-screenshot goes
   on the window container. The overlay must be NOT_TOUCHABLE or it swallows the PC's injected touches.
7. **Stock `services.jar` is R8-optimised:** `static final` constants are inlined and their fields removed
   (`BAL_ALLOW_PERMISSION`), and some fields are gone (`WindowState.mOwnerUid`). Resolve values at runtime, e.g.
   through `balCodeToString(int)`, and keep fallbacks. AOT can inline small callees: `deoptimize()` callers.
8. Run `service check` **as root**. The shell domain can't see the service.
9. Locking the phone stops mirroring and Link to Windows asks to unlock. That's normal Link to Windows
   behaviour.
10. The privapp allowlist is **enforced at boot**. A missing privileged permission can stop the boot.
    Recovery: safe mode (volume down during boot).

## Known gaps

- The black screen's window-layer hook has no effect on stock (ART copies interface default methods into
  `PhoneWindowManager`). It's harmless; the ROM patch is a source edit.
- Apps: PC window resize is ignored and tasks are not resizable, like OnePlus.
