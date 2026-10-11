# 软件模拟器诊断

Android 16 API 36 x86_64，4 GiB RAM、4 核、无 KVM，TCG 与 SwiftShader。初次启动约 594 秒；初次安装在 sys.boot_completed 之前被拒绝。后续安装成功。

1080×1920 分辨率下桌面 Quickstep 和 System UI 出现 ANR，保存实际截图。已停止本次启动的桌面进程，关闭三类系统动画，并使用 adb wm size 480x800、wm density 160 降低渲染负担。未禁用测试或放宽断言。am start -W 曾超时；必须用后续 activityStarts 的实际结果判定应用启动。

world-test-memory.txt 是真实世界测试期间的一次 dumpsys meminfo -s 快照，不是模型峰值或应用峰值。主机模型峰值另记在 model-test-results.json。

初次设备测试由于 JUnit 测试返回类型错误未能运行；第二次实际运行 5 项，启动及不可访问 URI 检查通过，另外 3 项因 Android ICU 正则字面量右花括号未转义失败。完整失败日志保留；修正后的最终记录在上级 device-test-results.*。
