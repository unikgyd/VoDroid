# 参与开发与反馈

欢迎通过 [Issues](https://github.com/unikgyd/VoDroid/issues) 报告问题或讨论改进。提交代码前请阅读 [开发指南](docs/DEVELOPMENT.md) 及 [第三方声明](THIRD_PARTY_NOTICES.md)。

反馈请提供应用版本、手机型号、Android / ROM、运营商、SIM / eUICC 类型、OMAPI / USB / Root 通道、隧道模式及失败阶段，并说明最短复现步骤和预期结果。

仅分享必要且脱敏的日志片段。删除 IMSI、ICCID、EID、电话号码、短信正文、激活码、确认码、鉴权向量、密钥与 Root 授权令牌；原始设备资料保存在本机 `artifacts/`，不要强制加入 Git。

协议修改应附针对行为的测试及 RFC / 上游出处。保持可选 Root 功能与标准路径分离，不把具体卡槽或运营商写死。文档应区分单元测试、本地模拟和真实运营商互通。

提交前运行核心测试、相关 Android 构建 / Lint、`python scripts/check_repo.py` 和 `git diff --cached --check`。版本发布由维护者按 [发布指南](docs/RELEASING.md) 执行。
