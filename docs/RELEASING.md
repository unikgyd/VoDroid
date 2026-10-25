# 发布指南

本仓库默认分支为 `main`。本地准备、上传源码与公开发布是三个独立步骤。

## 1. 生成并备份固定签名

使用 JDK 17：

```sh
python scripts/signing.py init
```

只在首次执行。脚本生成 RSA-4096 PKCS#12 密钥及随机密码，存放于被忽略的 `signing/release.p12`、`signing/credentials.json`，不会覆盖已有密钥，也不会打印密码。**加密备份整个 `signing/` 目录到仓库之外**；它是后续 APK 覆盖更新所需的身份。不要将密钥、密码或 Base64 密钥放入 Git、Issue 或聊天记录。

调试 APK 使用调试签名，正式 APK 使用此固定签名。首次从调试版迁移到正式版可能需要卸载重装，会清除应用私有数据；先自行保存需要的设置和记录。之后正式版一直使用同一密钥。

## 2. 一次性配置 GitHub Actions Secrets

安装 [GitHub CLI](https://cli.github.com)，并使用有本仓库管理权限的账号登录：

```sh
gh auth login
python scripts/signing.py upload
```

上传脚本通过标准输入导入以下四个仓库 Actions Secrets，不输出密钥值：

| Secret | 内容 |
| --- | --- |
| `VODROID_KEYSTORE_BASE64` | `release.p12` 的 Base64 |
| `VODROID_STORE_PASSWORD` | 密钥库密码 |
| `VODROID_KEY_ALIAS` | 签名别名 |
| `VODROID_KEY_PASSWORD` | 私钥密码 |

也可以在仓库 **Settings → Secrets and variables → Actions** 配置同名项。仅写入 Secrets，不能写入公开 Variables。没有这些值时，Release 工作流会停止；普通 CI 构建无需这些值。

Actions 使用工作流自带的 `GITHUB_TOKEN` 创建 Release，不需要另设发布 PAT。PR 的 Build 工作流只有仓库读取权限，不使用发布密钥；首次公开前可在 GitHub 设置中确认 Actions 允许执行本仓库工作流。

## 3. 本地预检

更新 `version.properties`，递增 `versionCode`，并维护 [CHANGELOG.md](../CHANGELOG.md)。例如当前开发版本 `0.4.6-dev`，正式标签为 `v0.4.6`。

```sh
git add .
python scripts/check_repo.py
git diff --cached --check
python scripts/release.py --tag v0.4.6
```

已缓存依赖时可给 `release.py` 加 `--offline`。该命令只在本机构建，不上传、不打标签。它执行核心测试、Release 构建、Lint、签名及产物一致性检查，输出：

| 文件 | 用途 |
| --- | --- |
| `vodroid-0.4.6.apk` | 可直接安装的正式签名 APK |
| `vodroid-magisk-0.4.6.zip` | 包含同一 APK 的可选 Magisk 模块 |
| `vodroid-0.4.6-source.zip` | 含固定原生依赖的对应源码，可独立构建 |
| `BUILD_INFO.json` | 版本、提交、SDK / ABI、证书 SHA-256 与依赖版本 |
| `SHA256SUMS` | 上述文件的 SHA-256 |

完整源码包从 Git 暂存清单收集当前源码，并展开两个固定子模块；先暂存所有待发布文件。正式发布时应保持工作区干净，使源码、提交与构建产物一致。GitHub 自动生成的 Source code ZIP 不含子模块，不能替代该源码包。

可用 `apksigner verify --verbose --print-certs` 验证 APK 签名。比较证书指纹以确认后续更新持续使用同一发布密钥。

## 4. 上传源码与标签

人工检查暂存内容后首次提交：

```sh
git commit -m "Prepare VoDroid for public release"
git push -u origin main
```

等待 **Build** 工作流通过，再发布版本：

```sh
git tag -a v0.4.6 -m "VoDroid 0.4.6"
git push origin v0.4.6
```

**Release** 工作流会重新检查版本、构建并签名，发布 APK、模块、完整源码、构建信息与校验和。`v0.4.6-rc.1` 等含 `-` 的标签自动标为预发布。

手动运行 Release 仅支持已存在的版本标签，适合首次失败后重跑；若同名 Release 已存在，创建步骤会拒绝覆盖，需先核对已有产物。不要移动已经公开发布的版本标签。

## 5. 发布后核对

从 Release 下载产物，比对 `SHA256SUMS`、APK 版本与证书指纹，确认模块内 APK 与独立 APK 一致。安装后检查作者 / 仓库入口和基本导航，再按 [兼容性记录](COMPATIBILITY.md) 验证设备功能。更新 README 中的实测范围时只记录实际验证结果。
