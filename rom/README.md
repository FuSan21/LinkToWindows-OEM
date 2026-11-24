# `rom/` — custom ROM drop-in (LineageOS 24 / Android 17)

Install steps are in the [main README](../README.md#b-custom-rom-lineageos-24--android-17). This file is the
reference: what each piece is and where it ends up on the device.

## Files

| Path | Ends up as | What |
|---|---|---|
| `ltw.mk` | — | `PRODUCT_PACKAGES` for everything below. Inherit from your product |
| `BoardConfigLtw.mk` | — | Adds `sepolicy/private` to `SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS` (path-independent) |
| `aidl/` | — | `android.app.ICrossDeviceService` + 5 interfaces. Transaction codes match OnePlus OxygenOS 15 (code 19 `getDeviceTemperature` is our addition) |
| `device-private/` | `/system_ext/framework/com.microsoft.device-private.jar` + permissions XML | Shared library holding `android.app.CrossDeviceManager`. deviceintegrationservice requests it with `<uses-library required=false>` |
| `server/` | linked into `services.jar` | system_server copy of the AIDL (`ltw-crossdevice-aidl-server`, used by patch 0001) |
| `broker/` | `/system_ext/app/CrossDeviceServiceBroker/` | `com.microsoftsdk.crossdeviceservicebroker`, **platform-signed**: no-prompt MediaProjection, clipboard, launch, black screen V2 (`BlackScreen.java`) |
| `prebuilt/DeviceIntegrationService/` | `/system_ext/priv-app/DeviceIntegrationService/` | Microsoft's APK, **kept Microsoft-signed**. You supply it |
| `prebuilt/LinktoWindows/` | `/system_ext/priv-app/LinktoWindows/` | Link to Windows (`com.microsoft.appmanager`), Microsoft-signed, preinstalled like OnePlus. **Required:** it must be privileged or Android 17 kills its MediaProjection. Play updates replace it. You supply it |
| `permissions/` | `/system_ext/etc/{permissions,default-permissions,sysconfig}/` | privapp allowlist (deviceintegrationservice + Link to Windows), POST_NOTIFICATIONS default grant, hidden-API exemption for deviceintegrationservice (as OnePlus' `hidden-api-whitelist-ext.xml`) |
| `virtual_keyboard/` | `/system_ext/bin/ltw_virtual_keyboard` + init rc | `vendor.virtual_keyboard` (`com.microsoftsdk.IVirtualKeyboardService`): uinput keyboard for PC typing. Re-implements OnePlus `/system/bin/virtual_keyboard` |
| `sepolicy/private/` | system_ext sepolicy | `cross_device_service` and `vendor.virtual_keyboard` service labels, daemon domain |
| `frameworks_base/` | — | Canonical `CrossDeviceService.java` / `LtwRemoteTaskManager.java`. The patches create exactly these files, and `module/` compiles them too. Edit here, then regenerate the patches |
| `patches/frameworks_base/` | `frameworks/base` | see below |
| `apply-patches.sh` | — | `git am`s the patches, skipping applied ones; `--check` = dry run in a temporary worktree |
| `local_manifest.xml` | `.repo/local_manifests/` | Pulls this repo to `vendor/ltw` |

## frameworks/base patches

Generated against `LineageOS/android_frameworks_base` `lineage-24.0` @ `d0a4045e`; apply in order.

| Patch | What |
|---|---|
| 0001 | `cross_device_service` in SystemServer (`CrossDeviceService`), `deviceintegration.permission.MANAGE_CROSS_DEVICE` in framework-res, `getSystemService("cross_device_service")` fetcher that loads `CrossDeviceManager` from the caller's ClassLoader |
| 0002 | Window types 2098/2099 (drag-drop / black-screen overlays) allowed for deviceintegrationservice (OnePlus `PhoneWindowManagerExtImpl.overrideCheckAddPermission`), real window layer |
| 0003 | Black screen: SKIP_SCREENSHOT (kept off the PC mirror; also via `WindowState.setClientSurface` for Android 17 client surfaces), NOT_TOUCHABLE + trusted overlay (PC input passes), seamless rotation |
| 0004 | Power key dismisses the black screen (`LtwBlackScreenController`, cf. OnePlus `BlackScreenWindowManager`) |
| 0005 | **Apps (Remote Task)**: `LtwRemoteTaskManager` + hook in `ActivityTaskManagerService.startActivityAsUser`; one trusted virtual display per streamed app |
| 0006 | Apps: notification launches (`PendingIntentRecord.sendInner`), background-activity-start allowlist for Link to Windows as PendingIntent sender (OnePlus `isFromBackgroundWhiteList`), app opened on the phone reported as display switch |
| 0007 | Apps: secure content (FLAG_SECURE focused window) reported to deviceintegrationservice |
| 0008 | Apps: duplicate launch focuses the existing window; app-to-app (new task) gets its own window |
| 0009 | Black screen V2: the broker may add window 2099; power key asks the broker to hide its black screen |

## Design notes

- `android.app.CrossDeviceManager` is **not** in `framework.jar`: there, every method deviceintegrationservice
  calls would be a hidden API and get blocked. It lives in the `com.microsoft.device-private` shared library
  (app ClassLoader), and `SystemServiceRegistry` instantiates it from the caller's ClassLoader.
- system_server compiles its own copy of the same AIDL. The two copies never share a ClassLoader.
- The broker needs the platform certificate: `MANAGE_MEDIA_PROJECTION` and `READ_CLIPBOARD_IN_BACKGROUND` are
  signature permissions on Android 17. So are `MONITOR_INPUT` (black screen V2: deviceintegrationservice
  uses the broker's black screen when the broker holds it) and `USE_BIOMETRIC_INTERNAL` (lets fingerprint
  touches through while an authentication runs).
- Microsoft's APKs are never re-signed. deviceintegrationservice gets its privileges from being a priv-app
  and the allowlist.

## Unverified on a real ROM build

- The broker gets `deviceintegration.permission.ACCESS_DEVICE_INTEGRATION` (knownSigner|signature, defined by
  deviceintegrationservice) through its platform signature. The consent screen needs it. The root module
  grants it explicitly.
- Virtual keyboard sepolicy: whether `priv_app` must be `priv_app_36` (Android 17 runs priv-apps in per-SDK
  domains).
- The privapp allowlist is exact for deviceintegrationservice 2.24112 / 2.25044. A newer version that
  requests a new privileged permission will stop the boot until you add it.

## Regenerating the patches

Keep a `frameworks/base` checkout with the patches applied, change it, commit, then
`git format-patch -<n> -o vendor/ltw/rom/patches/frameworks_base/`. If you change `CrossDeviceService.java` or
`LtwRemoteTaskManager.java`, copy the result to `rom/frameworks_base/`, so the module stays in sync.
