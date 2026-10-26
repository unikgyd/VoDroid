# 开发指南

## 环境

- JDK 17；Gradle 8.14.3（仓库 Wrapper）。
- Android SDK platform 35、Build Tools 35.0.0、NDK 28.2.13676358。
- Python 3.10+，构建脚本只依赖标准库；Git。
- 设备 Android 13+；APK 与本地库仅构建 arm64-v8a。

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;28.2.13676358'
git submodule update --init --recursive
```

设置 `JAVA_HOME` 和 `ANDROID_HOME`。也可通过 Android Studio 打开根目录，并由本机 `local.properties` 指定 SDK 路径。

两个 Git 子模块固定到构建所用版本：`lpac/src/main/jni/lpac` 与 `lpac/src/main/jni/cjson/cjson`。不要直接在 GitHub 的通用源码 ZIP 上构建，它不包含子模块内容；使用带子模块的 Git 克隆，或 Release 中专门提供的 `vodroid-*-source.zip`。

## 调试构建

Windows：

```powershell
.\scripts\build.ps1
# 已缓存依赖时可加 -Offline；本机失效的全局代理可加 -DirectNetwork。
```

Linux / macOS：

```sh
./gradlew --no-daemon :core:test :app:assembleDebug :app:lintDebug
python3 scripts/package.py --variant debug
```

报告位于 `core/build/reports/tests/test/` 和 `app/build/reports/lint-results-debug.html`。打包脚本校验版本、最低 Android 版本、仅 arm64-v8a 的本地库、APK v2 签名及模块内 APK 一致性。调试版与正式版的可安装成品都输出到根目录 `dist/`；Gradle 中间文件和检查报告保留在各模块的 `build/` 中。

主应用 APK 同时是 LSPosed 模块，Magisk ZIP 内也包含同一个 APK，不单独生成重复的 LSPosed APK。完成打包后，会依据上一份校验和清理未修改的旧成品，避免不同版本或调试 / 正式 APK 混在一起；手动放入的其他文件不作清理。

CI 在 `main` 提交与 PR 上执行相同的测试、调试构建、Lint 和打包检查，只上传检查报告。正式包使用本地签名编译后手动上传，参见 [发布指南](RELEASING.md)。

## 提交前

```sh
git add .
python3 scripts/check_repo.py
git diff --cached --check
git diff --cached --stat
```

检查暂存文件时，`dist/` 成品、实机调试资料、原始截图、二进制安装包、参考仓库、SDK 路径和 `signing/` 均应被忽略。文档用图只提交到 `docs/images/`，并先检查是否包含用户数据。忽略规则不删除本地文件，仓库使用 `main` 分支。

`scripts/check_repo.py` 检查暂存清单、固定子模块、Wrapper 校验和、明显凭据与 APK 内许可的一致性。它不能代替人工检查日志脱敏和新增依赖许可。

## Android 检查

```sh
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e layout home io.vodroid.test/io.vodroid.SmokeInstrumentation
adb shell am instrument -w -e layout publication io.vodroid.test/io.vodroid.SmokeInstrumentation
```

需要 ARM64 设备或 ARM64 模拟器，x86_64 模拟器不受支持。`home` 检查首页导航；`publication` 检查作者及仓库链接，拦截浏览器跳转。完整无参数测试包含样本消息与配置，仅允许模拟器运行。真实手机只使用显式诊断模式，操作前确认没有通话，读卡和网络探测前先断开当前会话。不要把实机原始输出提交到仓库。

## 版本与源码

版本来源为根目录 `version.properties`。每次公开更新必须增加 `versionCode`；本地发布脚本通过 `--version` 指定正式产物的 `versionName`，例如 `0.4.6-dev` 对应正式版本 `0.4.6`。模块版本由实际构建版本写入。正式签名与手动发布见 [RELEASING.md](RELEASING.md)。

`BUILD_INFO.json` 记录实际 ABI、提交与 `sourceDirty`。开发打包允许存在未提交修改，公开上传前应提交源码后重新打包，使构建信息对应最终提交。

代码与第三方修改遵循现有许可。更新上游时同时更新 Git 子模块提交、`scripts/project.py` 的固定版本和第三方声明；原生依赖修改应单独记录来源。
