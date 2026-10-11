# Minecraft 地图汉化器

独立 Android 应用，包含真实离线翻译、Bedrock 专用 LevelDB JNI、磁盘小端 NBT、工作副本导入、对照审阅和验证后导出。**0.1.0 是开发版本，不覆盖全部 Bedrock 格式；NPC 数据库对话仍未支持，Minecraft 游戏内重新导入尚未验收。**

安装包：项目根目录 `MinecraftWorldTranslator-debug.apk`，约 630 MiB（660,459,414 字节）。已包含五个模型、源/目标 SentencePiece 分词器和 ONNX Runtime。没有 INTERNET、MANAGE_EXTERNAL_STORAGE 或传统外部存储权限，不使用 WebView、Root、ML Kit Translation 或服务端翻译。

## 安装、语言和使用流程

支持 Android 8.0 / API 26 以上，目标 Android 16 / API 36，包含 arm64-v8a 和 x86_64；不支持 32 位设备。通过系统安装器或 `adb install MinecraftWorldTranslator-debug.apk` 安装。

选择 `.mcworld`、ZIP 或地图文件夹 → 自动验证工作副本 → 选择英语、俄语、德语、法语或西班牙语 → 提取文字 → 自动翻译 → 搜索和对照编辑 → 验证并导出新 `.mcworld`。

可以重新翻译单项、手动修改、保留原文、取消后台任务、查看无法处理的来源及原因、导入和导出 JSON 翻译表。失败和未处理项目不标记为成功，导出时保持原文。模型从 APK 解包到私有目录，安装后无需联网或额外下载。中文界面随系统使用深色模式。

SAF 只读取源地图；写回在第二份副本上进行。写回后关闭数据库并重新打开、逐记录比较，再压缩、解压和重新读取验证，最后写入用户新建的 SAF 文档。可通过 FileProvider 分享已验证的缓存文件。

## 存储和处理限制

- APK 约 630 MiB，全部模型清单文件为 **565,745,621 字节**（约 540 MiB）；完整私有模型缓存还需约 540 MiB。小地图建议预留 2 GiB，大地图另需解压数据约 3–4 倍空间。
- ZIP：解压总量最多 2 GiB、20,000 条目、路径深度 32、压缩比 200。拒绝路径越界、重复或 Unicode/大小写等价名称、文件目录冲突及 CRC 损坏。支持单一外层目录，拒绝 Java 地图。
- NBT 单记录最多 32 MiB、应用解析节点最多 100,000；UTF-8 文本文件和翻译表最多 8 MiB；审阅文字和翻译表最多 100,000 项。超过审阅上限的来源整条保留并报告。
- 模型输入最多 256 词元，生成最多 128 词元，必须到达 EOS；超限、空输出、未知词元或校验失败保留原文。
- WorkManager 和 dataSync 前台服务提供进度和取消。系统后台限制或进程终止可能中断任务；审阅数据保存在私有目录。失败/取消清理本次临时副本，原地图不变。

## 模型、来源及许可证

最终内置 OPUS-MT Marian 的量化 ONNX，未使用 NLLB。英语直接译为简体中文；俄、德、法、西经英语中转。完整分词、encoder/decoder 推理、贪心生成和源/目标词表映射均真实执行；中文使用 `>>cmn_Hans<<` 目标词元。

| 原模型 | 作用 | 完整目录大小（字节） | 模型卡许可证 |
| --- | --- | --- | --- |
| Helsinki-NLP/opus-mt-en-zh | 英语 → 简体中文 | 116,103,323 | Apache 2.0 |
| Helsinki-NLP/opus-mt-ru-en | 俄语 → 英语 | 114,801,586 | CC-BY 4.0 |
| Helsinki-NLP/opus-mt-de-en | 德语 → 英语 | 108,605,934 | Apache 2.0 |
| Helsinki-NLP/opus-mt-fr-en | 法语 → 英语 | 110,142,751 | Apache 2.0 |
| Helsinki-NLP/opus-mt-es-en | 西班牙语 → 英语 | 116,092,027 | Apache 2.0 |

量化转换来自对应的 Xenova 仓库。精确提交、大小和摘要见 [model-manifest.json](docs/model-manifest.json)。每个模型目录保留原模型卡、转换说明；APK 的 `assets/licenses` 保留完整许可证和归属声明。下载比较上游 LFS SHA-256 或 Git blob 摘要，应用首次解包再校验 SHA-256。量化、英语中转及贪心生成会影响质量，必须人工审阅。

候选 NLLB-200-distilled-600M 的官方语言表满足语言范围，许可证为 CC-BY-NC 4.0。用户接受该限制，但最终选择体积更小、许可更宽松的 OPUS。NLLB 未执行推理，不能引用不存在的内存或成绩。调研原材料及早期网络诊断在 `docs/evidence` 和 `docs/model-access-results.json`。

## 数据格式和命令保护

使用 [Amulet-Team/leveldb](https://github.com/Amulet-Team/leveldb) C++ 分支，固定提交 `1352243bb27d287b27be94f7591218dddb3ef900`，BSD 3-Clause；支持 Bedrock raw-zlib、legacy zlib、Snappy、zstd。没有假设普通 iq80 LevelDB 兼容。完整依赖源码、许可证及提交在 `native/vendor` 和 `native/provenance.json`。Amulet 的非商业 Python 绑定仅用于开发交叉验证，不属于 APK。

小端 NBT 解析磁盘类型 1–12，校验长度、编码、节点数及深度，只按已解析的类型和路径重编码目标字符串及长度，其他字节不变；禁止数据库二进制字符串替换。未知类型/结构、损坏记录和超限记录保留并报告。

| 来源 | 当前处理 | 验收范围 |
| --- | --- | --- |
| 旧式 Sign Text | 提取、写回、格式保护 | 真实 Bedrock 1.18 世界已做主机验证；设备记录单独列出 |
| FrontText / BackText / HangingSign | 已识别路径处理 | 代码和类型检查，未用真实新版地图验收 |
| CommandBlock Command | 仅显示命令文本 | 解析器测试；没有真实命令方块地图验收 |
| 书籍 title / author / pages.text | 限 written_book / writable_book，检查 compound/list | 代码检查；没有真实书籍地图验收 |
| NPC 数据库内 Dialog / Actions 等 | **未支持未验证的版本，保留并报告** | 不猜测未知持久化格式 |
| 附带 NPC dialogue JSON | 对话、显示名、按钮名、显示命令 | schema 来自微软官方 NPCDialogue 文档，未游戏内验收 |
| .mcfunction | 逐行提取显示命令 | 保留其他命令、注释及换行 |
| .lang | 只处理 key=value 的 value | key 和注释不变 |
| 其他 JSON | rawtext.text、有 translate 的字符串 with 参数 | 未识别 schema 不改；translate、selector、score 等结构保留 |

识别 say、tell/msg/w、tellraw、title、titleraw 及已知新旧 execute 嵌套语法。未知条件和参数保留并报告。JSON 按原始跨度写回，未修改字段、空白、键和结构保持不变。保护格式代码、选择器、资源 ID、换行、printf 格式、花括号变量、美元变量和转义序列；翻译后校验失败保留原文。

只接受 level.dat 存储头版本 8–10，不代表全部 Minecraft 游戏版本已验证。没有修改资源标识符或脚本逻辑。

## Android Studio 构建

打开仓库根目录，使用 JDK 21、SDK Platform 36、Build Tools 36.0.0、NDK 27.2.12479018、CMake 3.22.1。Gradle Wrapper 8.13 带官方 SHA-256；AGP 8.10.1、Kotlin 2.1.21。`local.properties` 只保存本机路径。

```bash
./gradlew assembleDebug
./gradlew :core:test
./gradlew :app:assembleDebugAndroidTest
```

标准 APK 在 `app/build/outputs/apk/debug/app-debug.apk`，交付副本为 `MinecraftWorldTranslator-debug.apk`。core 是可测试的格式/安全核心，storage 提供 SAF、DocumentFile、FileProvider 和 JNI，app 使用 Compose、Material 3、MVVM、Coroutines、WorkManager。模型已随项目提供；开发阶段重新下载可用 `python3 tools/download_models.py`，这不是运行期步骤。代理云环境可用 `tools/run_gradle.py`，正常 Android Studio 不需要该脚本。使用任务已有隔离 checkout，不创建额外 Git worktree。

## 测试记录

2026-10-11，实际测试、代码检查和未测试内容分别记录：

- **主机真实模型**：五语种均输出中文、到达 EOS。逐条原文、中转文、译文及时间见 [model-test-results.json](docs/model-test-results.json)。Linux x86_64 进程峰值 RSS **486,900 KiB**，约 475.5 MiB；它不是 Android 内存实测。建议至少 4 GiB RAM，Android 翻译测试的一次快照为 PSS 431,402 KiB、RSS 543,276 KiB，见 `docs/device-diagnostics/translation-memory-snapshot.txt`；这不是峰值，不能作为最低内存验收。
- **主机真实世界**：Amulet-Core 固定提交 `fad173683012f95cc892afd16dc2a95ee7c4697f` 的真实 Bedrock 1.18 fixture，共 **48,210 条数据库记录**。Kotlin 修改真实告示牌，由独立 NBT 库交叉读取，其他记录逐字节一致；导出重新导入、中文外层目录和原 fixture 未修改均通过。见 [real-world-test-results.json](docs/real-world-test-results.json)。这是明确的手动写回测试，不是模型翻译模拟。
- **构建**：实际 assembleDebug 成功，APK 包含 10 个 ONNX 图。arm64/x86_64 的 JNI 和 ONNX Runtime ELF LOAD 对齐均为 16 KiB。APK 全部模型摘要及签名验证通过；lint 0 个错误、5 个警告（备份配置、图标及 KTX 风格建议）。
- **核心测试**：命令保护、嵌套 execute、rawtext、占位符、NBT 重写和拒绝路径、Zip Slip、CRC 损坏、Unicode/大小写重复名、压缩比/大小/深度限制、20,001 条目、取消清理。25 项全部通过，数量及其他验证结论在 [validation.json](docs/validation.json)。安全单元测试使用合成小型二进制/ZIP 输入，不能冒充真实地图验收。
- **Android 实测**：Android 16/API 36 x86_64 软件模拟器，关闭 Wi-Fi/移动数据后 **5 项全部通过**，包含五语种真实推理及格式占位符还原、原生数据库写回导出重读、不可访问 SAF URI 清理、嵌套命令保护和界面启动。见 [device-test-results.json](docs/device-test-results.json) 及 instrumentation/logcat；本轮软件模拟测试耗时 1,692.948 秒，不能代表真机性能。首次安装过早、JUnit 返回类型及 Android ICU 正则兼容性失败日志均保留，修正后独立重试。最终补齐许可证的 APK 与该轮测试包的 DEX、原生库、Manifest、资源及模型摘要一致；最终包安装启动的单独记录为 `docs/final-apk-device-verification.json`。软件模拟器的 System UI 仍有 ANR，`am start -W` 曾超时；Activity instrumentation 通过且实际中文界面已绘制，截图和系统诊断保留在 `docs/device-diagnostics`，不能据此宣称真机界面稳定性已经验收。
- **未验收**：Minecraft 游戏内重新导入、Android 8、arm64 真机、真实 SAF 授权撤销和空间耗尽实验，以及新版 NPC/书籍/告示牌的真实地图验收。

主机复现：安装 ONNX Runtime 1.22.0、SentencePiece 0.2.0 和 NumPy，执行 `tools/test_offline_models.py`。世界复现：安装 `tools/requirements-validation.txt`，将 Amulet-Core checkout 固定到上述提交，执行 `tools/verify_real_world.py --fixture-checkout PATH --work NEW_DIRECTORY`；原 fixture 不会修改。

设备复现：构建 AndroidTest APK，再执行 `tools/device_validation.py --adb PATH --apk MinecraftWorldTranslator-debug.apk --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk --fixture-checkout PATH --output docs/device-test-results.json`。脚本复制真实 fixture 到应用私有测试目录，核验实际执行数量，不安装或伪造 Minecraft。


## 仓库下载

APK 和 ONNX 模型使用 Git LFS 保存。克隆前安装 Git LFS，运行 `git lfs install`，克隆后运行 `git lfs pull`。GitHub 的源码 ZIP 可能只包含 LFS 指针；请使用 Git 克隆获取完整模型与 APK。
