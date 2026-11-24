# Changelog

## v1.0.0

First release: root module for stock Android 17 and LineageOS 24 drop-in. Phone screen, Apps, black
screen (phone touches blocked), PC keyboard, clipboard, audio, drag & drop.

- Root module: LtwEmu (the LSPosed/Vector part) is included in the module zip; with Vector it is enabled
  and scoped automatically. One zip to install.
- Root module: in-app updates in KernelSU/Magisk (`updateJson`); updates reuse the installed Microsoft APKs.
- Root module: **Action** button runs a health check.
- Root module: SELinux rule follows deviceintegrationservice's target SDK (`priv_app_<sdk>`).
- ROM: three feature patches (core, black screen, Apps) carrying only call sites; the code moved to
  `rom/services` and is shared with the root module (`LtwWindowPolicy`).
- ROM: clear build error when a Microsoft APK is missing.
- CI: shellcheck, weekly check that the patches apply to `lineage-24.0`, `SHA256SUMS`, pinned actions.
- Root module: Link to Windows no longer needs a separate APK: the installed Play version (base + split
  APKs) becomes the system copy. A missing Microsoft app never stops the install; the Action button
  finishes the setup (opens Play for Link to Windows) one step per tap.
- Root module: the Action button reboots by itself (10 s countdown) when a setup step needs it,
  including after Vector enabled LtwEmu; never twice for the same step.
