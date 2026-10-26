# 本地编译与手动发布

公开版本在本机编译、签名后，手动上传到 [GitHub Releases](https://github.com/unikgyd/VoDroid/releases)。所有 APK 和本地库仅支持 **arm64-v8a**。CI 保留构建与检查，不创建 Release，也不使用发布密钥。

## 1. 准备环境与固定签名

安装 JDK 17、Android SDK 35、Build Tools 35.0.0、NDK 28.2.13676358 和 Python 3.10+，设置 `JAVA_HOME`、`ANDROID_HOME`。依赖配置见 [开发指南](DEVELOPMENT.md)。

首次生成签名：

```sh
python scripts/signing.py init
```

已有 `signing/release.p12` 和 `signing/credentials.json` 时直接使用，不需要重新生成。脚本不会覆盖已有密钥，也不会打印密码。**加密备份整个 `signing/` 目录到仓库之外**；后续版本必须使用同一密钥才能覆盖更新。该目录被 Git 忽略，只留在本地，无需配置 GitHub 签名 Secrets。

调试 APK 与正式 APK 签名不同。从调试版迁移到正式版可能需要卸载重装，会清除应用私有数据；先自行保存需要的设置和记录。

## 2. 编译与打包

更新根目录 `version.properties`，每次公开更新增加 `versionCode`，并维护 [CHANGELOG.md](../CHANGELOG.md)。例如开发版本 `0.4.6-dev` 对应正式版本 `0.4.6`。

检查并提交待发布源码：

```sh
git add .
python scripts/check_repo.py
git diff --cached --check
git diff --cached --stat
git commit -m "Prepare 0.4.6 release"
git push -u origin main
```

已有对应提交时无需重复提交。确认工作区没有待发布的遗漏修改，再编译正式包：

```sh
python scripts/release.py --version 0.4.6
```

`--version` 的基础版本应与 `version.properties` 一致。已缓存依赖时可以加 `--offline`。该命令运行核心测试、ARM64 Release 构建、Lint、APK 签名验证与打包检查，所有成品统一输出到根目录 **`dist/`**：

| 文件 | 用途 |
| --- | --- |
| `vodroid-0.4.6.apk` | Android 13+、arm64-v8a 的正式签名主应用，同时包含 LSPosed 模块 |
| `vodroid-magisk-0.4.6.zip` | 包含同一 APK 的可选 Magisk 模块 |
| `vodroid-0.4.6-source.zip` | 含固定原生依赖的对应源码 |
| `BUILD_INFO.json` | 版本、提交、`sourceDirty`、ABI、证书 SHA-256 与依赖版本 |
| `SHA256SUMS` | 上述文件的 SHA-256 |

LSPosed 使用同一份 `vodroid-0.4.6.apk`：安装后在 LSPosed 中启用「VoDroid」并选择作用域。Magisk 系统集成使用对应 ZIP。两者无需额外生成或安装第二个插件 APK。

调试构建也使用 `dist/`。再次打包时会清理校验和中记录且未修改的旧成品，目录中的主应用、模块和构建信息保持对应；自行放入的其他文件不作清理。

源码包根据 Git 跟踪清单收集当前源码，并展开两个固定子模块。新增和删除的文件应先通过 `git add` 更新清单。上传前检查 `BUILD_INFO.json`：`abis` 应为 `["arm64-v8a"]`，`sourceDirty` 应为 `false`，`commit` 应对应本次发布的源码提交。若编译后又修改源码或文档，应提交修改并重新打包。

## 3. 手动上传

1. 确认对应源码已推送到 `main`，CI 检查通过。
2. 核对 `dist/` 内的打包结果、版本、提交与签名指纹。
3. 打开仓库 **Releases → Draft a new release**，创建对应标签，例如 `v0.4.6`，选择本次源码提交。
4. 填写更新说明，将上述五个文件拖入附件区。测试版本可勾选预发布，确认后手动发布。

无需 GitHub CLI 或签名 Secrets，推送版本标签也不会自动创建 Release。GitHub 自动附带的 Source code ZIP 不含子模块，应同时上传本地生成的完整源码包。

## 4. 核对与后续更新

下载 Release 附件，比对 `SHA256SUMS`、APK 版本与证书指纹，确认模块内 APK 与独立 APK 一致。打包脚本会拒绝混入其他 ABI，并在写入模块后再次比对 APK 字节。

需要独立检查时，可使用 Android Build Tools 中的 `apksigner verify --verbose --print-certs` 验证 APK，并通过 `BUILD_INFO.json` 对照证书 SHA-256。后续正式版的证书指纹应保持一致。

下次发布递增版本、继续使用原有签名密钥，重复本地编译与手动上传流程。真实功能验证范围见 [兼容性说明](COMPATIBILITY.md)。
