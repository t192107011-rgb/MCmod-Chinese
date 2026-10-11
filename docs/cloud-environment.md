# 当前云环境

按 cloud-environment-onboarding:setup 使用现有 /workspace/MCmod-Chinese checkout，没有创建额外 worktree。用户随后明确授权应用开发，所以本次同时包含项目源代码修改。

JDK 21、Gradle 8.13、Android Platform/Build Tools 36、NDK 27.2.12479018、CMake 3.22.1、Android 16 AOSP x86_64 系统镜像及模型验证 Python 依赖已安装。工具和缓存在 /workspace/tooling；项目通过 SDK 的 local.properties 使用这些本机路径。Gradle、SDK/NDK 下载保留官方摘要验证，TLS 验证未关闭。

实际构建和测试见同目录 validation.json、build-output.txt、core-test-results.xml 和 device-test-results.*。模拟器没有 KVM，使用 TCG/SwiftShader；实际设备诊断和显示设置在 device-diagnostics。

已经保存的环境配置草稿字段：开发阶段模型来源的 network.allowed_domains，以及完整 start_skill（工具路径、现有 checkout、构建命令、模拟器启动及 sys.boot_completed 就绪检查、真实 fixture 验证说明）。没有新增密钥或凭据要求。草稿工具返回 saved、requires_publish=true。

保存草稿不代表应用到运行实例、执行启动脚本或发布环境。复用配置前应在环境设置中审阅保存并发布；尚未在新任务中验证快照恢复。APK 已内置模型，应用运行不依赖这些联网设置。
