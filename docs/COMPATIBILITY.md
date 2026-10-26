# 兼容性与验证状态

以下状态区分代码实现、自动测试与真实网络验证；实机结果不代表所有运营商可用。

## 安装范围与构建验证

文档更新：2026-10-26。当前应用要求 Android 13（API 33）及以上，调试版、正式版和配套 Magisk 模块均仅包含 **arm64-v8a** 本地库。

| 检查 | 已有验证结果 |
| --- | --- |
| 调试版 / 正式版 | 本地编译与签名验证通过 |
| 本地库 | APK 仅含 arm64-v8a；ELF 架构确认为 AArch64 |
| 配套模块 | 模块内 APK 与独立 APK 字节一致 |
| 交付文件 | SHA-256 校验通过，源码包包含固定原生依赖 |
| 协议测试 | 95 项 JVM 测试通过 |

上述结果来自本地构建检查。Android 页面及导航另有历史模拟器 / 实机验证，不能据此推断新运营商、配置下载或真实通话已通过互通。正式版本采用本地签名、手动上传；CI 负责构建检查。

## 已完成的实机验证

设备为 OPPO Android 13，使用插入手机的可插拔 eUICC 与 DITO 配置：

- OMAPI 身份、真实 EID 与配置列表读取；Root 指定卡槽的只读 APDU。
- 应用内隧道沿现有 VPN 连接 ePDG，EAP-AKA、CHILD_SA 与 IMS 注册。
- 本机发起 CHILD_SA 与 IKE_SA 换钥，换钥后重新 REGISTER。
- 真实短信接收、通知、系统收件箱单次入库；RP-ACK 获得 SIP 202，观察期间没有重传，用户确认系统短信只有一条。
- LSPosed 作用域内的 eSIM 能力查询、真实 EID、安装调用转接及一次取消回调；没有实际下载配置。
- 首页导航、可选增强开关隔离、Root 检测目标可选、音频本地回环及路由恢复。

## 协议范围

| 项目 | 实现与限制 |
| --- | --- |
| ePDG | 当前卡 CarrierConfig、MCC/MNC 域名、DNS 多地址及端口尝试、手动覆盖；未实现运营商专有开户 |
| IKE 策略 | Auto 采用匹配配置，否则优先 MODP14；只有明确协商拒绝才有限回退。Modern 固定 MODP14，Legacy 固定 MODP2 |
| 算法 | AES-CBC 128/256、HMAC-SHA1-96 / SHA256-128，PRF SHA1 / SHA256；尚无 GCM / ECDH |
| AKA | AKA / AKA′ 完整鉴权、AUTS、MAC、KDF、防降级、CHECKCODE 与保护结果通知；AKA′ 有标准向量测试，实机当前使用 AKA |
| IKE 分片 | RFC 7383，乱序、重复、缺片和完整性测试通过；当前实机 ePDG 未协商分片 |
| SA 换钥 | IKE / CHILD 双向换钥、PFS、重传、旧密钥窗口和硬超时；同时发起采用临时失败与退避，未实现双后继 SA 的完整碰撞淘汰 |
| 内层网络 | 应用内 IPv4、UDP、TCP、分片 / 重组；外层可解析 IPv6，内层尚不支持 IPv6 |
| IMS | UDP / TCP、AKAv1-MD5、注册刷新与备用地址；`ipsec-3gpp` 为 null encryption + HMAC-SHA1 / MD5，尚无 SIP TLS 或其他 IMS ESP 套件 |
| 短信 | RP-DATA / ACK / ERROR、GSM7 / UCS-2 接收、UCS-2 发送与长短信；未实现 TP-STATUS-REPORT、二进制短信、非 BMP 字符发送 |
| 通话 | SIP / SDP / RTP、G.711、设备 MediaCodec AMR / AMR-WB octet-aligned；尚缺 EVS、SRTP、复杂抖动处理及完整会话计时器 |

`standard-3gpp` 是运营商默认配置的名称，不是另一种加密套件。鉴权失败不会自动触发算法降级。DITO 实测协商 AES-128 / SHA1 / MODP2。

## 自动验证与待测

协议核心目前有 **95 项 JVM 测试**，覆盖鉴权向量、APDU、分片与换钥、ESP、SIP、短信事务 / 去重、通话和内层网络。Android Lint 纳入 CI；本地模拟与测试不等同于运营商互通。

仍待验证：更多运营商 / ROM、真实呼入呼出与双向音频、短信发送、实际 eSIM 下载与切换、USB 热拔插和长时间待机。AKA 快速重认证、MOBIKE / 换网恢复、IPv6 内层和更多算法尚待补充。

系统 eSIM 映射和系统发信桥接是实验能力。曾观察到系统映射与其他卡片管理器的通道争用；默认不开启，优先单独验证标准 OMAPI。

协议依据：[RFC 7296](https://www.rfc-editor.org/rfc/rfc7296)、[RFC 7383](https://www.rfc-editor.org/rfc/rfc7383)、[RFC 4187](https://www.rfc-editor.org/rfc/rfc4187)、[RFC 5448](https://www.rfc-editor.org/rfc/rfc5448)、[RFC 9048](https://www.rfc-editor.org/rfc/rfc9048)。
