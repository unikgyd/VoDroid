# Third-party notices

## OpenEUICC

Source: https://gitea.angry.im/PeterCxy/OpenEUICC (mirror: https://github.com/estkme-group/openeuicc).

Source revision used for the adapted files: `89d677e07d0ebbd2d157ede2cb31f76aec737e3a`.

Copyright 2022–2026 OpenEUICC contributors.

- USB CCID/APDU sources under `lpac/src/main/java/im/angry/openeuicc`: GPL-3.0-only. The upstream files identify their OpenKeychain ancestry.
- Kotlin lpac-jni sources, JNI bridge and GSMA certificate resources: upstream LGPL-2.1, see `lpac/LICENSE`. The overall combined application is GPL-3.0-only.
- Hidden eUICC type declarations originated in Android Open Source Project, Apache-2.0, with upstream notices retained in each file.
- No OpenEUICC artwork or upstream application branding is used.

Local changes: Android 13 minimum, pinned build configuration, separate physical-transport ownership, serialized card access, disabled raw APDU logs, strict HTTPS adapter, bounded CCID response parsing/time extensions, voltage-mask and PPS checksum corrections, power-off header correction, logical channels 4–19, native profile-list cleanup and initialization-failure cleanup.

## libeuicc from lpac

https://github.com/estkme-group/lpac

Pinned commit: `50cdf15ab40942b00f8819a1c2ca19997f1a6d34`.

Only the library sources in `euicc/` and the cJSON extension are compiled; their license files and SPDX headers are retained. `euicc/LICENSE` contains LGPL-2.1. lpac's standalone application and commercial license alternatives are not linked into the Android application.

## cJSON

https://github.com/DaveGamble/cJSON

Pinned commit: `6d9f2443ab071f86e5d9b43025a40929ec41c46c`.

MIT license, retained at `lpac/src/main/jni/cjson/cjson/LICENSE`.

## VoCat

https://github.com/MengMengCode/VoCat was consulted for the ePDG/IKE/EAP/IMS sequence. No VoCat source files are compiled, copied into this repository or included in distributed artifacts. Its source-available Research & Evaluation License is not relicensed by this project. Local reference checkouts are excluded from publication.

## Compile-only hook API

`hook-api/` contains minimal declarations matching the legacy Xposed binary API. These declarations throw if executed and are excluded from runtime artifacts. Optional hooks require a separately installed compatible LSPosed runtime. `hidden-api/` is also compile-only; implementations are supplied by Android.

Kotlin and kotlinx.coroutines are obtained as Gradle dependencies under their respective upstream licenses.

Full GPL-3.0 and third-party notices are included in APK assets. Additional LGPL-2.1, MIT and Apache-2.0 license texts are in `app/src/main/assets/licenses/`; native source distributions retain upstream license files.
