> **Note:** these are the working notes from the original research repository. Paths there map to this
> repository as follows: `oem/vendor/ltw/` → `rom/`, `oem/patches/` → `rom/patches/`,
> `oem/frameworks_base/` → `rom/frameworks_base/`, `oem/emulation/` → `module/` (the "emulation" is the
> root module). Install instructions are in the [README](../README.md).

# Link to Windows — full-feature OEM integration for AOSP / Pixel

Build plan for implementing the **OEM side** of Microsoft's Link to Windows contract
natively, assuming you are an OEM with **platform (native) signing** and **system/priv-app
placement**. No runtime hooking. Grounded in the reverse engineering done in this repo's
session (see `memory/nonsamsung-broker-path.md`).

---

## 0. The core model

Link to Windows "full features" (screen mirror, app streaming, clipboard, cross-device) is
**three layers**:

1. **User apps — Microsoft-signed, you DO NOT modify or re-sign:**
   - `com.microsoft.appmanager` — "Link to Windows" (pairing, messages, calls, notifications, mirror UI).
   - `com.microsoft.deviceintegrationservice` — the privileged companion that brokers mirror/app-stream/clipboard.
2. **OEM framework API — you build, in the boot classpath:**
   - `android.app.CrossDeviceManager` + its AIDLs (`ICrossDeviceService`, `IRemoteTaskCallback`, …). This
     is the **API contract** deviceintegrationservice binds to. OnePlus ships it inside `oplus-framework.jar`
     (boot classpath) — **not** as a `com.microsoft.device-private` shared library (see §8).
3. **OEM system pieces — you build, platform-signed:**
   - `cross_device_service` binder in **system_server** + a **Remote Task** patch in window management
     (app streaming), and a tiny platform-signed **`CrossDeviceServiceBroker.apk`** (MediaProjection mint,
     clipboard, launch). Phone-screen capture itself is done by deviceintegrationservice using the minted
     projection.

> §1–§6 were written before the OnePlus reference was decompiled. Where they disagree with **§8**, §8 wins.

The apps are **capability-neutral**; they light up features purely by discovering and calling (2)/(3).
Because (2)/(3) are platform-signed, they hold the signature/privileged permissions natively — no
permission hacks needed.

---

## 1. Exact API surface to implement (reconstructed this session)

### 1a. `android.app.CrossDeviceManager` — `getSystemService("cross_device_service")`
Backed by a binder system service. deviceintegrationservice calls these (static ones are `invoke-static`):

- **static** `isFeatureSupported(String):boolean`  ← must return true for `feature_basic` or OemService.onBind throws `SecurityException: feature feature_basic is not support`
- **static** `getAssetCategory():int`, `getMaxFps(int):int`, `isThreeStageVolumeDevice():boolean`, `notifyConnectionStateChange(boolean):void`
- instance: `addRemoteTaskCallback(IRemoteTaskCallback)`, `removeRemoteTaskCallback(IRemoteTaskCallback)`,
  `registerTaskChangeListener(ITaskChangeListener)`, `unregisterTaskChangeListener(ITaskChangeListener)`,
  `getProximityService():IProximityService`, `getRfcommOemManager():IRfcommOemManager`,
  `injectInputEvent(InputEvent,int)`, `setDisplayInfo(int,int,int)`, `updateSurface(String,Surface)`,
  `wakeUp(long)`, `getThermalStatus():int`, `getTopRunningPackageName():String`,
  `getDeviceTemperature()/getOverheatThreshold()/getResumeOverheatThreshold():Bundle`,
  `isInstantHotspotCapable()`, `isResumableFromOverheat()`, `getPermissionGranted(int)/setPermissionGranted(int)`,
  `setSecurityToken(String)`.

The hand-written stub signatures already in `app/src/stubs/java/android/app/` are the correct shapes —
turn those into the **real** implementation.

### 1b. `com.microsoftsdk.crossdeviceservicebroker.ICrossDeviceServiceBroker` (10 methods)
Already reconstructed as AIDL in `broker/src/main/aidl/...`. Transaction order is load-bearing:
`hasPrimaryClip, getPrimaryClipDescription, getPrimaryClip, addClipboardListener,
getMediaProjectionIntent(int displayId, String pkg), launch(Intent,Bundle), removeClipboardListener,
showBlackScreen(int,int,int), hideBlackScreen, setBlackScreenListener`.
Discovered by `queryIntentServices(action = ICrossDeviceServiceBroker-interface-name)` — publish it as
a service with that intent-filter.

### 1c. Other AIDL the apps reference
`android.app.IRemoteTaskCallback`, `IServiceRemoteTaskInstance`, `IProximityService`,
`IRfcommOemManager`, `ITaskChangeListener` (Remote Task / app-streaming lifecycle), and
`com.microsoftsdk.IVirtualKeyboardService` (`attach/detach/sendKey` for PC→phone input, normally the
`vendor.virtual_keyboard` HAL). Stub signatures already in `app/src/stubs/java/android/app/`.

### 1d. Activation gates in appmanager (must all pass)
appmanager activates its generic-OEM path (`ExtGeneric`) only when
`ExtGenericFunctionProvider.isSupported()` = **both**:
- `OemServiceManager.checkService()` — deviceintegrationservice's `IOemService` is bindable (action
  `com.microsoft.deviceExperiences.aidl.IOemService`), AND
- `isDeviceExperiencesAvailable()` — deviceintegrationservice declares
  `com.microsoft.deviceExperiences.DeviceExperiencesContentProvider` AND **holds**
  `deviceintegration.permission.MANAGE_CROSS_DEVICE` (signature|privileged).

With both apps present + privileged, these pass natively.

---

## 2. Deliverables to build

1. **`com.microsoft.device-private.jar`** (framework library)
   - Contains the API classes above (CrossDeviceManager + the AIDL interfaces + the broker interface).
   - Platform-signed; placed `/system/framework/com.microsoft.device-private.jar`.
   - Declared `/system/etc/permissions/com.microsoft.device-private.xml`:
     `<permissions><library name="com.microsoft.device-private" file="/system/framework/com.microsoft.device-private.jar"/></permissions>`.
   - The two Microsoft apps load it via `<uses-library android:name="com.microsoft.device-private" android:required="false"/>` (already in their manifests).

2. **`CrossDeviceService`** — the implementation (platform component, holds signature perms):
   - **No-consent MediaProjection**: `IMediaProjectionManager.createProjection(uid, pkg, TYPE_SCREEN_CAPTURE, false, displayId)` → token packed under `EXTRA_MEDIA_PROJECTION` (code already in `broker/.../MediaProjectionMinter.java` / `app/.../ProjectionMint.java`). Needs `MANAGE_MEDIA_PROJECTION` (platform-signed → native).
   - **Phone-screen capture**: hand appmanager a projection of display 0, OR implement
     `IPhoneScreenMirroringManager.createVirtualDisplay(...surface...)` to mirror display 0 onto the
     encoder surface via `MediaProjection.createVirtualDisplay` / `SurfaceControl` display mirroring.
   - **App streaming (Remote Task)** *(superseded — OnePlus does NOT use VDM, see §8d)*: create per-app virtual displays via **AOSP `VirtualDeviceManager`**
     (`CREATE_VIRTUAL_DEVICE`) + a `CompanionDeviceManager` association, launch apps onto them, report
     task lifecycle through `IRemoteTaskCallback`/`ITaskChangeListener`. (deviceintegrationservice already
     has an AOSP-VDM code path — `p079n1/b.java` `VirtualDeviceProxy` — this is the model to drive.)
   - **Input injection**: `InputManager.injectInputEvent` (`INJECT_EVENTS`) + the virtual-keyboard service.
   - **Black screen**: privileged overlay window on display 0 during streaming.
   - **Clipboard**: `ClipboardManager` + `READ_CLIPBOARD_IN_BACKGROUND`.
   - **Registration**: register as `cross_device_service` (so `getSystemService` returns it) AND publish
     `ICrossDeviceServiceBroker` (intent-filter) so deviceintegrationservice binds it.
   - **Where it runs**: cleanest = a platform-signed, persistent **priv-app** holding the perms via
     `privapp-permissions`. Alternative = patch into `system_server` (SystemServiceManager) — more
     invasive, needs sepolicy for a new system service.

3. **Privileged placement + privapp-permissions**
   - `com.microsoft.appmanager` and `com.microsoft.deviceintegrationservice` → `/system/priv-app/`
     (or `/product/priv-app/`), each in its own dir with its APK.
   - `/system/etc/permissions/privapp-permissions-<oem>.xml` granting the privileged perms the two apps
     request: `MANAGE_CROSS_DEVICE`, `CAPTURE_AUDIO_OUTPUT`, `READ_PRIVILEGED_PHONE_STATE`,
     `REAL_GET_TASKS`, `MODIFY_AUDIO_ROUTING`, `TETHER_PRIVILEGED`, `OVERRIDE_WIFI_CONFIG`, etc.
   - Define the custom perms (`deviceintegration.permission.MANAGE_CROSS_DEVICE`,
     `deviceintegration.permission.ACCESS_DEVICE_INTEGRATION`) — deviceintegrationservice declares them;
     grant them to the right uids.

4. **SELinux policy** — contexts + rules for `cross_device_service` and the priv-app (if it opens
   SurfaceFlinger/input/MediaProjection internals). Only needed if system_server-side; a priv-app with
   the right perms mostly rides existing `priv_app` domain.

5. **Device config / feature flags** — any `<feature>` or system-property the OEM asserts (optional;
   our RE shows the gate is the ContentProvider + perm, not a manifest feature).

---

## 3. Investigate existing OEM images (the shortcut — do this first)

Get a **OnePlus / OPPO / HONOR / Surface Duo** build that ships Link to Windows. Pull and decompile:

| Artifact | Path on OEM image | Why |
|---|---|---|
| **`com.microsoft.device-private.jar`** | `/system/framework/` (+ its `.xml` in `/system/etc/permissions/` or `/system/framework/`) | **The reference implementation of §1.** Decompile with jadx → exact CrossDeviceManager/broker method bodies and which framework APIs they call (`DisplayManagerGlobal`, `SurfaceControl`, `InputManager`, `VirtualDeviceManager`, `MediaProjectionManager`). Port onto AOSP. |
| privapp-permissions for the MS apps | `/system/etc/permissions/privapp-permissions-*.xml` or `/product/.../` | Exact privileged-perm allowlist to copy. |
| app placement | `/system/priv-app/` or `/product/priv-app/` | Confirm priv-app, not /app. |
| `deviceintegrationservice` + `appmanager` APKs | priv-app dirs | Match/raise the versions; confirm signatures. |
| sepolicy | vendor/precompiled sepolicy | `cross_device_service` service_contexts + te rules. |
| the `cross_device_service` registration | boot (`service list`), init rc | How/where the service is published. |

Extraction commands (from a full OTA / factory image):
```bash
# unpack system image (payload_dumper or simg2img + mount)
simg2img system.img system.raw.img && sudo mount -o ro,loop system.raw.img /mnt/sys
cp /mnt/sys/framework/com.microsoft.device-private.jar .
cp /mnt/sys/etc/permissions/*privapp*.xml .
cp /mnt/sys/etc/permissions/com.microsoft.device-private.xml .
jadx -d dpriv com.microsoft.device-private.jar   # read CrossDeviceManager / broker impls
```
Note: appmanager carries per-OEM variants (`EXT`, `EXTGENERIC`, `EXTHNS`=HONOR, `EXTOP`=OPPO…). The
**generic `EXTGENERIC`** path is the cleanest to target; HONOR/OPPO builds are the best decompile
references because their device-private is less entangled than Samsung's `Sem*` framework.

---

## 4. Signing & placement rules

- **Platform-sign** `com.microsoft.device-private.jar` and `CrossDeviceService` with YOUR platform key
  → they hold signature-level perms (`MANAGE_MEDIA_PROJECTION`, `CAPTURE_VIDEO_OUTPUT`,
  `INJECT_EVENTS`, `CREATE_VIRTUAL_DEVICE`, `INTERNAL_SYSTEM_WINDOW`…) natively.
- **Do NOT re-sign the Microsoft apps.** Keep their Microsoft signature (their MSA auth / attestation
  and deviceintegrationservice's SHA-256 caller allowlist `h2.c.isCallerAllowed` depend on it — appmanager
  must stay Microsoft-signed to pass that check). Grant their privileged perms via **privapp-permissions
  allowlist**, which is placement-based, not signature-based.
- ~~Priv-app / platform components are exempt from hidden-API enforcement automatically.~~ **Wrong:**
  as far as I know, AOSP (`ApplicationInfo.isAllowedToUseHiddenApis`) exempts only platform-signed
  apps, and system apps that set `usesNonSdkApi` or are listed as `<hidden-api-whitelisted-app>` in
  sysconfig. I haven't re-checked this against lineage-24.0. A Microsoft-signed priv-app gets none of
  these by default. §9 sidesteps the question by shipping `CrossDeviceManager` in an app-loaded shared
  library, not `framework.jar`.

---

## 5. Build order (each milestone independently testable)

1. **Place + privilege the two MS apps** (priv-app + privapp-permissions). Verify: install clean, no
   permission denials, messages/calls/notifications work (these need no OEM framework).
2. **Ship a loadable `com.microsoft.device-private`** (even stubbed classes that resolve). Verify:
   appmanager enters ExtGeneric, deviceintegrationservice starts without `NoClassDefFoundError`. *(We
   proved this exact step works this session via injected stubs.)*
3. **Implement MediaProjection + display-0 capture** in CrossDeviceService. Verify: **phone-screen
   mirroring shows on Windows** (the piece we were finishing — the empty-projection bug at
   `g.onPermissionResult` disappears because the OEM now supplies a real projection).
4. **Implement the Remote Task WM patch (§8d) + Remote Task callbacks.** Verify: **"Apps" streaming**
   (individual apps on virtual displays). *(Not VirtualDeviceManager — OnePlus uses DisplayManager virtual
   displays created inside system_server plus activity-launch interception; see §8d.)*
5. **Input injection + clipboard + black screen + audio.** Verify: full feature parity.

---

## 6. Reuse from this session (already proven / in-repo)

- **Activation-gate map** (§1d) and the full crash/gate cascade — `memory/nonsamsung-broker-path.md`.
- **Broker AIDL** — `broker/src/main/aidl/com/microsoftsdk/crossdeviceservicebroker/*.aidl`.
- **OEM framework stub signatures** — `app/src/stubs/java/android/app/*.java` (CrossDeviceManager + the 5
  interfaces; static-vs-instance already correct; `isFeatureSupported` must return true).
- **No-consent MediaProjection minting** — `broker/.../MediaProjectionMinter.java`, `app/.../ProjectionMint.java`.
- **Display-0 mirror via `MediaProjection.createVirtualDisplay(... surface ...)`** — `app/.../HookAppManager.java#createMirrorDisplay`.
- **Broker implementation (10 methods)** — `broker/.../CrossDeviceBrokerService.java` (clipboard, black
  screen, launch, getMediaProjectionIntent) — port into CrossDeviceService.

These were written for the rooted/Xposed route but are the correct *logic*; moving them into a
platform-signed framework + service removes every permission/hidden-API/stub workaround.

---

## 7. Reference implementation acquired — OnePlus 12 (OxygenOS 15 / Android 15)

A real OEM reference was extracted. **There is NO standalone `com.microsoft.device-private.jar`** — OnePlus/OPPO implement the cross-device framework directly in their **platform framework jars**. The `com.microsoft.device-private` `<uses-library required="false">` is simply absent/unsatisfied on the device; the apps work because the OEM patched `android.app.CrossDeviceManager` + `cross_device_service` into the framework.

### 7a. The source build
- Device: OnePlus 12 Global, model **CPH2581**, internal **OP595DL1**, SoC "pineapple".
- Build: OxygenOS **15.0.0.702 (EX01)**, **Android 15**, SDK 35, patch 2025-03-01. Fingerprint `OnePlus/CPH2581EEA/OP595DL1:15/AP3A.240617.008/...`.
- Why this build: modern (VirtualDeviceManager-era) AND still ships Link to Windows. Avoid OxygenOS 16 (LTW removed -> "PC Link"); avoid PJD110 (China/ColorOS).
- Get it: full OTA `.zip` (has `payload.bin`), or `dumps.tadiphone.dev/dumps/oneplus/op595dl1` (op595dl1 = CPH2581), pick an Android 14/15 branch.

### 7b. Where everything lives (partition map)
OnePlus/OPPO use `my_*` partitions for OEM content. Confirmed:
| Artifact | Partition / path |
|---|---|
| `android.app.CrossDeviceManager` (+ `$AssetCategory`) — the manager/API | `system/framework/oplus-framework.jar` |
| `com.android.server.wm.CrossDeviceService` (+ `CrossDeviceServiceDelegate`, `CrossDeviceServiceRUSHelper`) — system_server impl | `system/framework/oplus-services.jar` |
| `com.microsoftsdk.crossdeviceservicebroker.CrossDeviceServiceBroker` (+ inner `$1` Stub) — broker impl | `system/framework/oplus-services.jar` |
| `virtual_keyboard` (native input HAL = `vendor.virtual_keyboard`) | `system/bin/virtual_keyboard` |
| `DeviceIntegrationService.apk` (OEM preinstalled, privileged) | `my_stock/priv-app/DeviceIntegrationService/` |
| LTW privapp-permissions | `my_stock/etc/permissions/privapp_permission_ltw.xml` |
| LTW default-grant | `my_stock/etc/permissions/default_grant_permissions_ltw.xml` |
| OPPO privapp-permissions | `system/etc/permissions/privapp-permissions-oplus.xml` |

Note: `com.microsoft.appmanager` (Link to Windows) is NOT preinstalled — only `deviceintegrationservice` is a system app; appmanager is installed by the user from Play. Both jars contain real `classes.dex` (deodexed, decompilable — not odex stubs).

### 7c. Extraction procedure (reproducible, Windows + WSL)
- Tools: `pip install payload_dumper`; WSL (Debian) with `erofs-utils`.
- Steps:
  1. Pull payload: `7z e OTA.zip payload.bin -o/c/op12_extract`.
  2. Dump framework + OEM partitions (OPPO content is in `my_product`/`my_stock`, NOT system/product):
     `python -c "import sys;sys.argv=['x','/c/op12_extract/payload.bin','--partitions','system,system_ext,product,my_product,my_stock','--out','/c/op12_extract/out'];import payload_dumper.dumper as d;d.main()"`
  3. Images are EROFS (magic 0xE0F5E1E2 at offset 1024). Extract via WSL:
     `wsl -u root -- bash -lc "apt-get install -y erofs-utils; cd /mnt/c/op12_extract/out; for p in system system_ext my_stock; do mkdir ${p}_x; fsck.erofs --extract=${p}_x ${p}.img; done"`
  4. Artifacts are then under the `*_x/` trees.
- Gotchas: Git Bash needs `MSYS_NO_PATHCONV=1` for `wsl ... /mnt/c/...` paths; run WSL as `-u root` so apt/sudo doesn't hang on a password prompt; write WSL scripts to a file and run `wsl -u root -- bash /mnt/c/.../script.sh` to avoid `$` escaping.

### 7d. Decompiling the jars
jadx isn't in Debian apt and wasn't preinstalled. Options:
- REA MCP (worked): copy `oplus-services.jar` -> `oplus-services.apk` (REA's jadx provider requires `.apk`; the dex reads fine), then `inspect_android_class` / `inspect_android_method` with `path=...apk`, `class_name=...`. Inner Stub bodies are in `<Class>$1` (e.g. `CrossDeviceServiceBroker$1`).
- Or install standalone jadx: `jadx oplus-services.jar`.
Saved copies (gitignored, local) are in **`re/oneplus12-fw/`**: `oplus-framework.jar`, `oplus-services.jar` (+ `.apk` copies), `DeviceIntegrationService.apk`, the 3 permission XMLs.

### 7e. Confirmed reference: `getMediaProjectionIntent` (OnePlus exact impl)
```
public Intent getMediaProjectionIntent(int uid, String packageName) {
    IBinder binder = ServiceManager.getService("media_projection");
    IMediaProjectionManager pm = IMediaProjectionManager.Stub.asInterface(binder);
    IMediaProjection projection = pm.createProjection(uid, packageName, 0, false); // type=0 SCREEN_CAPTURE, permanent=false
    Intent intent = new Intent();
    intent.putExtra("android.media.projection.extra.EXTRA_MEDIA_PROJECTION", projection.asBinder());
    return intent;
}
```
Matches `app/.../ProjectionMint.java` and `broker/.../MediaProjectionMinter.java` exactly. OnePlus's broker (`CrossDeviceServiceBroker$1`) implements only 7 of 10 methods: `hasPrimaryClip, getPrimaryClipDescription, getPrimaryClip, addClipboardListener, removeClipboardListener, getMediaProjectionIntent, launch`. The black-screen trio is handled elsewhere (likely `CrossDeviceService`), not the broker.

### 7f. Exact LTW privileged-permission setup (copy into your privapp-permissions)
```
<!-- privapp_permission_ltw.xml -->
<privapp-permissions package="com.microsoft.appmanager">
  <permission name="android.permission.WHITELIST_AUTO_REVOKE_PERMISSIONS"/>
  <permission name="android.permission.READ_WALLPAPER_INTERNAL"/>
  <permission name="android.permission.REQUEST_COMPANION_PROFILE_COMPUTER"/>   <!-- app streaming via CompanionDeviceManager -->
  <permission name="android.permission.REQUEST_COMPANION_SELF_MANAGED"/>
  <deny-permission name="android.permission.CAPTURE_MEDIA_OUTPUT"/>
  <deny-permission name="android.permission.MODIFY_AUDIO_ROUTING"/>
  <deny-permission name="android.permission.CAPTURE_AUDIO_OUTPUT"/>
</privapp-permissions>
<privapp-permissions package="com.microsoft.deviceintegrationservice">
  <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE"/>
  <permission name="android.permission.CAPTURE_AUDIO_OUTPUT"/>
  <permission name="android.permission.MODIFY_AUDIO_ROUTING"/>
  <permission name="android.permission.REAL_GET_TASKS"/>
  <permission name="android.permission.TETHER_PRIVILEGED"/>
  <permission name="android.permission.OVERRIDE_WIFI_CONFIG"/>
</privapp-permissions>
<!-- default_grant_permissions_ltw.xml: deviceintegrationservice gets POST_NOTIFICATIONS -->
```
Key insight: the heavy signature perms (MANAGE_MEDIA_PROJECTION, CREATE_VIRTUAL_DEVICE, INJECT_EVENTS, INTERNAL_SYSTEM_WINDOW) are **NOT** granted to the apps — they are held by the platform `oplus-services` (`CrossDeviceService` in system_server), which does the privileged work for the apps. The apps get only `REQUEST_COMPANION_PROFILE_COMPUTER`/`SELF_MANAGED` (the CompanionDevice association that app-streaming's VirtualDeviceManager needs) plus a couple of reads.

### 7g. Research TODO (DONE 2026-11-24 — results in §8)
Decompile from `re/oneplus12-fw/oplus-services.apk` + `oplus-framework.apk` (REA, see 7d) and port to AOSP (Part 2 deliverables):
1. `android.app.CrossDeviceManager` (oplus-framework) — full public method list + how it marshals to `cross_device_service` (the internal AIDL). Turn `app/src/stubs/java/android/app/CrossDeviceManager.java` into the real thin client.
2. `com.android.server.wm.CrossDeviceService` (oplus-services) — **the core**: display-0 capture, app-streaming virtual displays (look for `VirtualDeviceManager`/`VirtualDeviceParams`/`CompanionDeviceManager`), input injection (`InputManager.injectInputEvent`), black screen, and how it registers `cross_device_service` + publishes `ICrossDeviceServiceBroker`. Read first the methods backing `CrossDeviceManager.updateSurface/setDisplayInfo/injectInputEvent/addRemoteTaskCallback/registerTaskChangeListener`.
3. `CrossDeviceServiceBroker$1` (oplus-services) — remaining broker methods (clipboard, launch) for parity.
4. `DeviceIntegrationService.apk` (re/oneplus12-fw) — OEM deviceintegrationservice; diff vs the APKMirror copy in `re/ms-device-integration/` for OEM-specific wiring.
5. Runtime placement: OnePlus runs `CrossDeviceService` inside **system_server** (`com.android.server.wm`), registered as `cross_device_service`, with `CrossDeviceManager` added to `SystemServiceRegistry`. Replicate via a frameworks/base patch, or (prototype) a platform-signed persistent service.

Cross-references: activation gates + the empty-projection `g.onPermissionResult` bug + crash cascade in `memory/nonsamsung-broker-path.md`; pivot rationale in `memory/oem-native-plan.md`; working (Xposed-route) implementations of the same logic in `app/`, `broker/`, `systemhook/`.

---

## 8. Decompiled reference — how OnePlus actually implements it (2026-11-24)

Decompiled with standalone **jadx 1.5.7** (`scoop install extras/jadx`; the whole jar decompiles in about a minute, which beats per-method REA calls):
`jadx -d src-services --no-res oplus-services.jar`, and the same for `oplus-framework.jar` and `CrossDeviceServiceBroker.apk`. The output lives in
`re/oneplus12-fw/src-{framework,services,broker,dis,fwres}/` (gitignored). `CrossDeviceServiceBroker.apk` and
`oplus-framework-res.apk` were also copied into `re/oneplus12-fw/`.

### 8a. Corrections to §0–§6

| Plan assumed | Reality on OnePlus 12 / A15 |
|---|---|
| A `com.microsoft.device-private.jar` shared library | **None.** `android.app.CrossDeviceManager` + AIDLs live in boot-classpath `oplus-framework.jar`. The `<uses-library required=false>` is simply unsatisfied. |
| The broker is a service in the OEM framework/system service | It is a **separate tiny APK** in `/system/app/CrossDeviceServiceBroker/` (package `com.microsoftsdk.crossdeviceservicebroker`), **platform-signed** (cert SHA-256 `FC:98:DA:…:43:86` = `framework-res`), not a priv-app. |
| deviceintegrationservice declares `MANAGE_CROSS_DEVICE` | The **platform** defines it: `oplus-framework-res.apk` (package `oplus`), `protectionLevel="system\|signature"`. deviceintegrationservice only *uses* it and gets it via `privapp-permissions-oplus.xml`. deviceintegrationservice itself defines only `ACCESS_DEVICE_INTEGRATION` (`knownSigner`, cert `E843EE3D…5E70`). |
| App streaming via `VirtualDeviceManager` + a CompanionDevice association | **No VDM.** system_server creates `DisplayManager` virtual displays and **intercepts `startActivity`** to redirect the app onto them (the "Remote Task" WM patch, §8d). |
| The OEM implements phone-screen capture (`createVirtualDisplay` of display 0) | **deviceintegrationservice does it itself.** It creates the `YourPhoneCompanion` VD from the projection the broker mints. The OEM only adds a rotation/transaction fix in `ContentRecorder` (§8e). |
| The black-screen trio is handled by `CrossDeviceService` | The broker implements **7/10** methods. `showBlackScreen/hideBlackScreen/setBlackScreenListener` are **not implemented anywhere** (the base Stub returns defaults). Black screen is optional. |
| `CrossDeviceManager` has `getDeviceTemperature/getOverheatThreshold/getResumeOverheatThreshold` | Not present on OnePlus. Thresholds are constants in `RemoteTaskConstants` (overheat 45 °C, resume 40 °C). |

### 8b. Component map → AOSP port deliverables

| # | OnePlus artifact | What it does | AOSP port target |
|---|---|---|---|
| 1 | `oplus-framework.jar`: `android.app.CrossDeviceManager`, `ICrossDeviceService`, `IRemoteTaskCallback`, `IServiceRemoteTaskInstance`, `ITaskChangeListener`, `IProximityService`, `IRfcommOemManager`, `RemoteTaskConstants` | Client API + AIDL | `frameworks/base/core/java/android/app/` (or a boot-classpath jar) |
| 2 | `oplus-framework.jar`: `ActivityOptionsExtImpl` | Parses 3 Bundle keys into ActivityOptions | Patch `ActivityOptions` (fields + `fromBundle`/`toBundle`) |
| 3 | `oplus-framework-res.apk` | Defines `deviceintegration.permission.MANAGE_CROSS_DEVICE` (signature\|privileged) | `frameworks/base/core/res/AndroidManifest.xml` |
| 4 | `SystemServerExtImpl.addCrossDeviceService` | `ServiceManager.addService("cross_device_service", new CrossDeviceService(ctx, ams.mActivityTaskManager))` | `SystemServer.startOtherServices` + sepolicy `service_contexts`/`service.te` |
| 5 | `oplus-services.jar`: `com.android.server.wm.CrossDeviceService` (+ `CrossDeviceDisplayInfo`, `TaskChangeNotifier`, `RfcommOemManager`, `ProximityService`) | The binder (§8c) | `frameworks/base/services/core/java/com/android/server/wm/` |
| 6 | `oplus-services.jar`: `RemoteTaskHandlerManager`, `RemoteTaskHandler`, `CrossDeviceServiceDelegate`, `RemoteTaskLaunchScenarioController`, `…SwitchDisplayController`, `…DuplicateController`, `…ConfigurationController`, `DefaultRemoteTaskController` (~1.6k lines) | App streaming (§8d) | Same package; port nearly verbatim, then wire the hooks in §8d |
| 7 | `/system/app/CrossDeviceServiceBroker.apk` | `ICrossDeviceServiceBroker` (§8f) | A platform-signed `android_app` (`certificate: "platform"`). Our `broker/` module is already this. |
| 8 | `ContentRecoderExtImpl.isLinkToWindowsMode` | Mirror rotation fix | Small `ContentRecorder` patch (optional at first) |
| 9 | `privapp-permissions-oplus.xml` + `privapp_permission_ltw.xml` + `default_grant_permissions_ltw.xml` | App privileges | `/system_ext/etc/permissions/` or `/product/etc/permissions/` |
| 10 | `/system/bin/virtual_keyboard` | PC→phone keyboard HAL | Later; check whether LTW actually needs it on non-OPPO devices |

So **frameworks/base patches are unavoidable for app streaming** (items 2, 4, 6 hook into ActivityStarter and friends). **Phone-screen mirroring needs only** items 1, 3, 4, 5 (minimal) and 7.

### 8c. `CrossDeviceService` (system_server binder) — method by method
- `onTransact` gate: `CrossDeviceManager.isCallerAllowed(ctx)`. This requires the caller to hold `MANAGE_CROSS_DEVICE` **and** `getNameForUid(callingUid)` ∈ `{"com.microsoft.deviceintegrationservice"}`. **appmanager never calls this service directly.**
- `injectInputEvent(ev, mode)` → `InputManager.getInstance().injectInputEvent` under `clearCallingIdentity`.
- `getTopRunningPackageName` → `ActivityManager.getRunningTasks(1)[0].topActivity.pkg`.
- `wakeUp(t)` → `PowerManager.wakeUp(t, WAKE_REASON_APPLICATION=2, …)`.
- `addRemoteTaskCallback(cb)` → registers in a `RemoteCallbackList`, then **immediately calls `cb.notifyDeviceAvailabilityStateChanged(8 /*FREE*/)`**. `onCallbackDied`/`removeRemoteTaskCallback` → `handleClientDied` → if no clients remain, `recycleAll()` the remote tasks and release the task listeners.
- `getThermalStatus` → average of `IThermalService.getCurrentTemperaturesWithType(2 /*TYPE_BATTERY*/)`; ≥45 → 1, else 0. `isResumableFromOverheat` → ≤40.
- `updateBackgroundActivityList(list)` → set, used to allow background activity starts (BAL) for those packages.
- `updateSurface(uuid, surface)` → `RemoteTaskHandlerManager.updateSurface` → `VirtualDisplay.setSurface`.
- `updateDisplayInfo(w,h,dpi)` (the manager's `setDisplayInfo` calls this) → stores the size used for new remote-task VDs.
- `setPermissionGranted(uid)/getPermissionGranted(uid)` → in-memory uid set.
- `setSecurityToken(s)` → stored. It must match the token in the launch options (§8d).
- `register/unregisterTaskChangeListener` → `TaskChangeNotifier`.
- `getProximityService`, `getRfcommOemManager` → OEM objects. `isFeatureSupported(feature_proximity)=false`, so proximity can be a stub.
- `isInstantHotspotCapable` → `TetheringManager.isTetheringSupported()`.

`CrossDeviceManager` static parts (client side, no binder): `isFeatureSupported` returns true for basic / remote_apps (false on fold or LightOS) / oem_rfcomm / recent_apps / instant_hotspot / audio_stream / calling / messaging, and **false for proximity**. `getAssetCategory`: 1 phone, 2 tablet, 3/4 fold. `getMaxFps`: 30 for type 1/2, else 15. `getCrossDeviceVersion`: 1. `notifyConnectionStateChange`: only OPPO telemetry, so it can be a no-op.

### 8d. App streaming = "Remote Task" WM patch
Flow:
1. appmanager builds ActivityOptions with Bundle keys `deviceintegration:remotetask.launchOption`=`32`, `…remotetask.uuid`=`<uuid>`, `…remotetask.security.token`=`<token>`. It sends them to deviceintegrationservice, which sets the same token via `CrossDeviceManager.setSecurityToken` and launches via `broker.launch(intent, options)` (the broker runs `startActivity(intent, options)`). *(Keys confirmed present in the appmanager dex; the exact appmanager→deviceintegrationservice handoff is not traced yet.)*
2. **`ActivityStarter` hook** (`ActivityStarterExtImpl.handleRemoteTaskIfNeeded`, called with `newTask`/`reusedTask`) → `RemoteTaskHandlerManager.activateRemoteTaskIfNeeded(...)`. This only runs if a client is registered (`isAnyClientAlive`).
3. `RemoteTaskLaunchScenarioController.checkRemoteTaskScenarios` → scenarios: `0` client-called (flag 32 **and token match**), `1` app-to-app (launched from an app already on a remote VD), `2` edge cases, `3` duplicate launch, `4` MD→VD (main display → virtual display).
4. On success: `new RemoteTaskHandler(uuid).prepareVirtualDisplay()` →
   `DisplayManager.createVirtualDisplay("MS_VIRTUAL_DISPLAY"+(n+1), w, h, dpi, /*surface*/null, flags=1161)`.
   1161 = `PUBLIC|OWN_CONTENT_ONLY|ROTATES_WITH_CONTENT|TRUSTED` (`TRUSTED` is system-only, hence system_server). It then calls `setDisplayState(true)` and `WindowManager.setDisplayImePolicy(id, LOCAL)`. The options are rewritten with `setLaunchDisplayId(id)` + the uuid, `getDisplayContentOrCreate(id)` is called, and `CrossDeviceService.activateRemoteTask(handler)` broadcasts `IRemoteTaskCallback.activateRemoteTask(IServiceRemoteTaskInstance)`.
5. deviceintegrationservice then calls `updateSurface(uuid, encoderSurface)` → `VirtualDisplay.setSurface`.
6. `RemoteTaskHandler` **is** the `IServiceRemoteTaskInstance`: `isTouchRequired`, `getOrientation`, `getRemoteTaskBounds`, `getRemoteTaskInstanceUUID/Intent/PackageId`, `isRemoteTaskInstanceShowingSecuredContent` (focus window `FLAG_SECURE`), `changeRemoteTaskFocus`, `removeRemoteTask` (→ `ATMS.removeTask`), `notifyRemoteDisplayOrientationChanged` (→ `setRequestedOrientation`), `notifyRemoteTaskInputEvent`.

Other hook sites to patch in AOSP (search `getRemoteTaskManager()` in `src-services`):

| AOSP class | Hook | Purpose |
|---|---|---|
| `ActivityStarter` | `handleRemoteTaskIfNeeded` (+ `queryPreferredDisplayArea`, `findTaskForReuseIfNeeded`, `isDeliverToCurrentTop`, `handleInterceptSessionIfNeeded`) | Redirect launch onto the remote VD; handle reuse / duplicate / display switch |
| `RootWindowContainer` | `findTaskOnlyForLaunch` | Reuse an existing remote task for launcher intents |
| `BackgroundActivityStartController` | `isFromBackgroundWhiteList(pkg)` | Allow BAL for `updateBackgroundActivityList` packages |
| `ActivityClientController` | `closeRemoteTask(taskId, 4)` | User close |
| `Task` / `ActivityRecord` finish, process death | `handleRemoveTask`, `handleFinishActivity`, `handleProcessDied` | `notifyRemoteTaskRemoved` with reason 4 (USER) / 5 (SYSTEM) |
| Recents | `interceptFromRecents` | VD→MD switch → `notifyDisplaySwitched` |
| Config / relaunch | `onConfigurationChanged`, `shouldIgnoreRelaunch` | Density/rotation on VDs |

**Porting strategy:** port the `RemoteTask*` classes nearly verbatim into `com.android.server.wm`, replacing OPPO's `getWrapper().getExtImpl()` indirection with direct fields. Add the `ActivityOptions` fields. Wire the hooks above, **starting with ActivityStarter only** (scenario 0, client-called), because that alone should make basic "Apps" streaming work. Add the other hooks after that.

### 8e. Phone-screen mirroring (milestone 3) — smallest OEM surface
- deviceintegrationservice asks the broker for `getMediaProjectionIntent(uid, pkg)`. The broker mints with `createProjection(uid, pkg, 0, false)` (needs `MANAGE_MEDIA_PROJECTION`, platform-signed → granted).
- deviceintegrationservice creates the `YourPhoneCompanion` VD itself from that projection.
- OEM extra: `ContentRecorder` treats displays named `YourPhoneCompanion` / `MS_VIRTUAL_DISPLAY*` as "LTW mode" and skips the shell-transition mirror sync on rotation. Treat this as optional. Add it only if rotation glitches show up.
- ⇒ **Milestone 3 needs:** the `CrossDeviceManager` API in the boot classpath (so deviceintegrationservice's `isFeatureSupported(feature_basic)` passes), a minimal `cross_device_service`, the platform-defined `MANAGE_CROSS_DEVICE`, and the platform-signed broker APK. **No WM patch.**

### 8f. `CrossDeviceServiceBroker.apk` (exact manifest)
```xml
<manifest package="com.microsoftsdk.crossdeviceservicebroker"> <!-- /system/app, platform cert, minSdk 29 -->
  <uses-permission android:name="android.permission.READ_CLIPBOARD_IN_BACKGROUND"/>
  <uses-permission android:name="android.permission.MANAGE_MEDIA_PROJECTION"/>
  <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
  <uses-permission android:name="deviceintegration.permission.MANAGE_CROSS_DEVICE"/>
  <uses-permission android:name="deviceintegration.permission.ACCESS_DEVICE_INTEGRATION"/>
  <queries><package android:name="com.microsoft.appmanager"/></queries>
  <application android:persistent="false" android:usesNonSdkApi="true">
    <service android:name=".CrossDeviceServiceBroker" android:exported="true"
             android:permission="deviceintegration.permission.MANAGE_CROSS_DEVICE">
      <intent-filter><action android:name="com.microsoftsdk.crossdeviceservicebroker.ICrossDeviceServiceBroker"/></intent-filter>
    </service>
  </application>
</manifest>
```
Body: clipboard getters + one `OnPrimaryClipChangedListener` → `ICrossDeviceClipboardListener.onPrimaryClipboardChanged()`, `getMediaProjectionIntent` (§7e), and `launch(intent, opts)` → `startActivity(intent, opts)` (returns false on `ActivityNotFoundException`). The black-screen methods are left unimplemented. **Our `broker/` module already covers this. It needs the package name, manifest and permission set above, a platform certificate, and the `/system/app` location.**

### 8g. Revised next steps
1. **Milestone 3 (mirroring), minimal platform:** add `CrossDeviceManager` + AIDLs to frameworks/base, define `MANAGE_CROSS_DEVICE` in framework-res, and register a minimal `CrossDeviceService` (the §8c methods, with remote-task parts stubbed). Ship the broker as a platform-signed `/system/app`, plus the priv-app/privapp XMLs from §7f, adding `MANAGE_CROSS_DEVICE` for deviceintegrationservice (as `privapp-permissions-oplus.xml` does).
2. **Milestone 4 (app streaming):** port §8d. Start with the ActivityStarter hook and scenario 0.
3. Still open: (a) trace appmanager→deviceintegrationservice for the exact token/uuid handoff; (b) `TaskChangeNotifier`, `RfcommOemManager`, `ProximityService` bodies (in `src-services`, not yet read); (c) whether `virtual_keyboard` is required; (d) the target AOSP tree/device. The port diff depends on the exact Android version.

*(Item 1 is now implemented in a different shape (§9); (b) TaskChangeNotifier was ported; (d) is decided: LineageOS 24 / lynx.)*

---

## 9. Target: LineageOS 24 (Android 17) on Pixel 7a `lynx` — milestone 3 implementation (2026-11-24)

**Why Lineage:** AOSP `android-17.0.0_r1` reportedly ships without Pixel device trees. LineageOS 24 officially supports lynx, so it gives us a buildable Android 17 tree. Our own release keys then act as the platform certificate.

**Deliverables:** in-repo under [`oem/`](../oem/README.md), with build and verify steps there.

### 9a. Design changes vs OnePlus (and why)
| OnePlus | Ours | Reason |
|---|---|---|
| `CrossDeviceManager` + AIDL in boot-classpath `oplus-framework.jar` | In the **`com.microsoft.device-private` shared library** (`/system_ext/framework`), which DIS already requests via `<uses-library required=false>` | Classes in the boot classpath are hidden APIs to a Microsoft-signed app. A shared library loads in the **app's** ClassLoader, so no hidden-API rules apply. Inside it, `ServiceManager.getService` is `@UnsupportedAppUsage`, so app code may call it. |
| `getSystemService("cross_device_service")` wired through OPPO's SystemServiceRegistry ext | `SystemServiceRegistry` fetcher that runs `ctx.getClassLoader().loadClass("android.app.CrossDeviceManager").newInstance()`, else returns `null` (no WTF) | DIS gets the manager via `context.getSystemService("cross_device_service")` (`l2/a.java`, `deviceExperiences/o.java`). The framework never links against the class. |
| system_server uses the boot-classpath AIDL | Same `.aidl` compiled into the static lib `ltw-crossdevice-aidl-server`, linked into `services.core` | Neither copy is in the boot classpath, so the two never collide. Same descriptors, so they interoperate. |
| Broker in `/system/app`, no BAL permission (OPPO hooks `BackgroundActivityStartController`) | `/system_ext/app`, platform cert, **+`START_ACTIVITIES_FROM_BACKGROUND`** | `launch()` starts activities while the broker is only bound. The platform cert lets it hold that permission without a WM hook. |
| `Intent.putExtra(String, IBinder)` (hidden) | `Bundle.putBinder` + `putExtras` | Hidden on Android 17. The result is the same extra. |
| `createProjection(uid, pkg, 0, false)` | `createProjection(uid, pkg, 0, false, DEFAULT_DISPLAY)` | Android 14+ signature (confirmed in lineage-24.0 `IMediaProjectionManager.aidl`). |
| — | `ICrossDeviceService` code **19 `getDeviceTemperature()`** | DIS 2.25044 calls `CrossDeviceManager.getDeviceTemperature/getOverheatThreshold/getResumeOverheatThreshold() : Bundle` (keys `deviceintegration:device.temperature`, `…overheat.suspend.threshold`=45, `…overheat.resume.threshold`=40). OnePlus's A15 build lacks these. Thresholds are answered client-side. |

**Facts confirmed in lineage-24.0** (frameworks/base @ `d0a4045e`):
- `MANAGE_MEDIA_PROJECTION` is `signature`, so the broker *must* be platform-signed.
- `createProjection` takes 5 args.
- `ServiceManager.getService` is `@UnsupportedAppUsage`.
- `InputManagerGlobal.injectInputEvent(ev, mode)`, `ATMS.getTasks(int)` and `ATMS.register/unregisterTaskStackListener` exist.
- `services.core` only globs `java/**/*.java` (hence the static lib).
- Lineage's product hook is `vendor/extra/product.mk`. There is no BoardConfig hook, so the sepolicy include goes in the device BoardConfig.
- DIS's broker arg 1 is a **uid** (`permission/g.java`: `getMediaProjectionIntent(Process.myUid(), getNameForUid(uid))`).
- The privapp allowlist is exact: every `signature|privileged` permission DIS 2.24112 and 2.25044 request, plus `MANAGE_CROSS_DEVICE`.

### 9b. Verification done (no device yet)
- The 9 AIDL files compile with SDK `aidl` 36.0.0 against the `android-37.2` `framework.aidl`.
- The generated transaction codes **match OnePlus 1:1** for all 6 cross-device interfaces (51 methods; only code 19 is extra) and for broker codes 1–7.
- `CrossDeviceManager`, `CrossDeviceServiceBroker` and `CrossDeviceService` compile under `javac --release 21 -Xlint:all -Werror` against `android-37.2/android.jar`, with stubs for hidden APIs.
- `oem/patches/frameworks_base/0001-*.patch` applies with `git am` onto the lineage-24.0 copies of the 4 touched files.
- **Not yet done:** a full `brunch lynx`, boot, or LTW pairing. Soong may still flag things javac cannot catch (Android.bp visibility, sepolicy neverallows, errorprone).

### 9c. Next
1. Build and boot it. Run the checks in `oem/README.md`, then try phone-screen mirroring from Windows.
2. Milestone 4: port §8d into `com.android.server.wm` as `frameworks_base` patch 0002:
   - add `ActivityOptions` remote-task fields,
   - call `CrossDeviceService.setRemoteTaskHost(...)`,
   - hook `ActivityStarter.startActivityInner` before `setInitialState`, for scenario 0 first.
   - Then flip `REMOTE_APPS_IMPLEMENTED`.
   - Watch for deadlock: OnePlus creates the VD while the WM lock is held (`prepareVirtualDisplay` runs inside `activateRemoteTaskIfNeeded`).

---

## 10. Validated on stock Android 17 via rooted emulation — phone-screen mirroring WORKS (2026-11-24)

Setup: [`oem/emulation/`](../oem/emulation/README.md). It uses files plus system_server hooks that
reproduce patches 0001/0002 and the platform signature, with **no hooks into Microsoft's apps**.
Result: Link to Windows natively activates ExtGeneric and binds deviceintegrationservice's
`OemService`. deviceintegrationservice gets `CrossDeviceManager` from `com.microsoft.device-private`,
talks to `cross_device_service`, binds the broker, shows its consent screen, and has the broker mint
a projection for Link to Windows. Link to Windows then streams `YourPhoneCompanion` to Windows.

**New OEM requirements found on device** (all now in the Lineage set unless noted):

| # | Requirement | Why / evidence |
|---|---|---|
| 1 | **Link to Windows preinstalled as a priv-app** | OnePlus ships `/my_stock/priv-app/LinktoWindows/LinktoWindows.apk` (§7b was wrong: `my_stock` holds it). Android 17 `MediaProjectionManagerService.start()` demands a `mediaProjection`-type FGS unless the owner `isPrivilegedApp()`; Link to Windows' FGS is `connectedDevice`, so a Play-only install loses the projection right after "Start the token instance". Play updates keep `PRIVILEGED` (`UPDATED_SYSTEM_APP`). Allowlist mirrors OnePlus `privapp_permission_ltw.xml` (deny the 3 audio-capture permissions) + 3 newer ones. |
| 2 | **Hidden-API exemption for deviceintegrationservice** | OnePlus `/system_ext/etc/sysconfig/hidden-api-whitelist-ext.xml` "Add for MS-LTW". This replaces the global `hidden_api_policy=1` hack. |
| 3 | **Window types 2098/2099** allowed for `MANAGE_CROSS_DEVICE` callers (patch 0002) | deviceintegrationservice's black screen (`TYPE_SYSTEM_BLACKSCREEN_OVERLAY` = 2099 in OnePlus `framework.jar`) crashed it with `BadTokenException`. OnePlus: `PhoneWindowManagerExtImpl.overrideCheckAddPermission` + `BlackScreenWindowManager`. |
| 4 | **Broker holds `ACCESS_DEVICE_INTEGRATION`** | Guards deviceintegrationservice's `MediaProjectionPermissionActivity`, which it launches via `broker.launch()`. That permission is `knownSigner\|signature` (knownCerts = Link to Windows' cert). On the Lineage build the broker's platform signature is *assumed* to cover it (OnePlus' broker requests it); **unverified, check on first boot.** |
| 5 | SELinux domain `priv_app_36` | Emulation only; Lineage's `app_api_service` type covers all app domains. |
| 7 | **`vendor.virtual_keyboard`** (DONE, validated) | OnePlus native uinput daemon `/system/bin/virtual_keyboard`. Interface `com.microsoftsdk.IVirtualKeyboardService`: `attach(token, name)`, `detach(token)`, `sendKey(token, down?1:0, scanCode)`, one client. Re-implemented as `oem/vendor/ltw/virtual_keyboard` (one C++ source: Soong `cc_binary` + init rc + own sepolicy domain on Lineage; NDK build + KernelSU `service.sh` in the emulation). On device: typing from the PC works and the soft keyboard stays hidden (Android classes it `KEYBOARD\|ALPHAKEY`). |
| 6 | Consent flow is two-phase | `requestRemoteAccessPermission(showPrompt=true)` shows the consent UI (returns an empty Intent), then `(false)` → `grantMediaProjection` → broker `getMediaProjectionIntent(LinkToWindowsUid, pkg)`. Both phases are native; no hook needed. |

| 8 | **Black screen (type 2099) kept off the mirror, out of input, dismissable** (DONE, validated; patches 0003/0004) | OnePlus `services.jar` treats 2099 like a rounded-corner overlay (`WindowStateAnimator` SKIP_SCREENSHOT, `WindowToken` setParent(null), seamless rotate, `InputMonitor.isTrustedOverlay`), and `BlackScreenWindowManager.interceptPowerKey` hides it via a custom `IWindow.dispatchBlackScreenKeyEvent`. On Android 17 the surface is client-created, so skip-screenshot goes on the window container in `WindowState.setClientSurface`. It must be NOT_TOUCHABLE or it swallows the PC's injected touches (deviceintegrationservice injects on display 0 via `CrossDeviceManager.injectInputEvent`). Power key: the window is removed server-side. On device: phone black, PC shows and controls the live screen, power press dismisses it. |
| 9 | **"Apps" / Remote Task, scenario 0** (DONE, validated; patch 0005, `LtwRemoteTaskManager`) | Link to Windows (`RTHContainer.launch`) calls `startActivity(intent, bundle)` itself with `deviceintegration:remotetask.{launchOption=32, uuid, security.token}` (the token it gave deviceintegrationservice via `IRthService.setSecurityToken` -> `CrossDeviceManager.setSecurityToken`). Intercepting the **raw Bundle in `ActivityTaskManagerService.startActivityAsUser`** (before `SafeActivityOptions`) is enough for this scenario: create the trusted VD `MS_VIRTUAL_DISPLAY<n>` (OnePlus flags 1161) and set `android.activity.launchDisplayId`; no `ActivityStarter` hook needed. deviceintegrationservice reads the display id from `IServiceRemoteTaskInstance.getBundle()` and sends the encoder surface through `updateSurface`. Trusted + non-private VD => any user-0 app may launch there (`isCallerAllowedToLaunchOnDisplay`). Pitfall: `registerTaskStackListener` inside the launch binder call needs `clearCallingIdentity`. On device: several app windows at once; PC close -> `removeRemoteTask` -> tasks removed, display released. |

| 10 | **Apps: notification launches + phone switch** (DONE, validated; patch 0006) | Link to Windows opens notifications with `PendingIntent.send(..., remote bundle)` (flag + token, no uuid) → hook `PendingIntentRecord.sendInner` (activity PIs only). The notifying app (creator) is in the background, so Android 17 BAL blocks it (`resultIfPiSenderAllowsBal: BAL_BLOCK`) even though the sender opted in: allow the **real caller** when it is on deviceintegrationservice's `updateBackgroundActivityList` (`com.microsoft.appmanager[.dev]`) and a client is connected (OnePlus `isFromBackgroundWhiteList`). Opening a streamed app on the phone moves its task to display 0 → `notifyDisplaySwitched(flag 0)` + release (OnePlus VD→MD). On device: Messenger notification (a trampoline activity) opens in a PC window. |
| 11 | **Apps: duplicate launch + app-to-app** (DONE, validated; patch 0008) | Duplicate (OnePlus scenario 3): a remote launch of an app that already owns a PC window reuses that display and reports `notifyDuplicateRemoteTask(needFocus=existing, intercept=new, 2)`; deviceintegrationservice focuses the existing window. App-to-app (scenario 1): an app in a PC window starting another app in a NEW_TASK gets its own display/window. Pitfalls: (a) compare with the window's *owning* (base) app, both for the caller (browser trampolines like Brave's IntentDispatcher run inside the caller's task) and for duplicates (a trampoline on top must not make a window look like it is owned by the target); (b) report a real package from `getRemoteTaskInstancePackageId` — implicit intents (links) gave null and Link to Windows then silently opened no window. Validated with Telegram/Facebook links -> Brave. |

**Open items:**
- **Option 2: block only physical touches on the black screen — DONE on the emulation via black
  screen "V2"** (`vendor/ltw/broker/.../BlackScreen.java`). deviceintegrationservice has two
  black-screen modes (`DependencyModule.provideBlackScreenController`): if the broker holds
  `MONITOR_INPUT` it calls broker `showBlackScreen(titleRes, descRes, iconRes)` / `hideBlackScreen` /
  `setBlackScreenListener` (listener only logs); otherwise ("legacy", what OnePlus ships) it adds its
  own touchable window 2099 and ORs MotionEvent flag `0x1000` onto injected PC events — OnePlus'
  Java never reads it, so presumably its native InputDispatcher skips 2099 for flagged events. Our
  broker now requests `MONITOR_INPUT` and draws a type-2099 window (skip-screenshot / not touchable
  as before) with DIS' own strings/icon, plus a **gesture monitor that pilfers every pointer stream
  from a physical device** (injected events carry deviceId -1). Same UX as DIS: 60 s burn-in fade,
  touch reveals, vertical swipe > 150 px hides; power key -> system_server broadcasts
  `HIDE_BLACK_SCREEN` to the broker. Exceptions: nothing is pilfered while the keyguard is locked
  (lock screen + fingerprint), and the UDFPS area is left alone while a fingerprint authentication
  runs (`IFingerprintService.registerAuthenticationStateListener`, `USE_BIOMETRIC_INTERNAL`) so
  BiometricPrompt works. Validated on device: phone touches blocked, PC input passes, swipe/power
  dismiss, lock-screen fingerprint, BiometricPrompt. Known limit: a phone touch during a PC drag
  cancels the PC drag too (pilfer is per window). **Lineage: patch 0009** allows the broker in `checkAddPermission` for 2098/2099
  (0002 allowed DIS only) and makes `LtwBlackScreenController.interceptPowerKey` broadcast
  `HIDE_BLACK_SCREEN` to the broker when it owns the window. The platform-signed broker gets
  `MONITOR_INPUT` (signature|recents) and `USE_BIOMETRIC_INTERNAL` (signature) from its certificate;
  no privapp allowlist entry is needed.
- **Milestone 4 follow-ups (Apps):** (secure content DONE, patch 0007: reported correctly; Windows renders
  it black, which is Link to Windows' UX; orientation VERIFIED: fullscreen video turns the PC window
  landscape via the ROTATES_WITH_CONTENT display + `getOrientation`; PC-initiated rotation
  `notifyRemoteDisplayOrientationChanged` is a no-op but **Link to Windows 1.26102 never calls it**
  (only `RTHContainer.setSize` -> `notifyRemoteDisplayResolutionChanged`, which OnePlus also ignores
  while reporting `isRemoteTaskResizeable() = false`); phone->PC (MD→VD) VERIFIED working without special
  handling. Left: OnePlus scenario 2 (edge cases).

