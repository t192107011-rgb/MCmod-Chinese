package cn.mcworld.translator

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                TranslatorScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslatorScreen(model: WorldViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var search by rememberSaveableState()
    var filterFailures by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TextEntry?>(null) }
    var draft by remember { mutableStateOf("") }
    var batchSize by remember { mutableIntStateOf(500) }
    var batchMenu by remember { mutableStateOf(false) }
    var customBatch by remember { mutableStateOf("") }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun permitNotifications() { if (Build.VERSION.SDK_INT >= 33) notification.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { permitNotifications(); model.import(uri, false) }
    }
    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { permitNotifications(); model.import(uri, true) }
    }
    val exportWorld = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            model.enqueue("export", "destination" to uri.toString())
        }
    }
    val exportTable = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            model.table(uri, false)
        }
    }
    val importTable = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.batchImport(listOf(uri))
    }
    val exportBatches = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            model.batchExport(uri, batchSize)
        }
    }
    val importBatches = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.batchImport(uris)
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Minecraft 地图汉化器") }) }) { insets ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("选择地图 → 验证 → 选择语言 → 提取 → 导入译文 → 审阅 → 验证导出", style = MaterialTheme.typography.bodyMedium)
                Text("完全离线 · 仅编辑工作副本", color = MaterialTheme.colorScheme.primary)
                Text("提取原文后可导出 JSON 翻译表，在外部填写译文，再导入并校验。", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importFile.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("选择地图 ZIP / .mcworld") }
                    OutlinedButton(onClick = { importFolder.launch(null) }, enabled = !state.busy) { Text("地图文件夹") }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.stage, style = MaterialTheme.typography.titleMedium)
                        Text(state.progress)
                        if (state.busy) {
                            if (state.progressTotal > 0) {
                                LinearProgressIndicator(progress = { (state.progressCurrent.toFloat() / state.progressTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                val percent = (state.progressCurrent * 100 / state.progressTotal).coerceIn(0, 100)
                                if (state.activeOperation == "export") {
                                    Text("$percent% · 导出地图")
                                } else {
                                    Text("$percent% · ${state.progressCurrent} / ${state.progressTotal} ${when {
                                        state.progress.contains("资源文件") -> "个资源文件"
                                        state.edition == "java" -> "个区域区块"
                                        else -> "条数据库记录"
                                    }}")
                                }
                            } else LinearProgressIndicator(Modifier.fillMaxWidth())
                            OutlinedButton(onClick = model::pause) { Text("暂停任务") }
                        }
                        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (state.world.isNotEmpty()) {
                item {
                    if (state.sourceName.isNotBlank()) Text("地图：${state.sourceName}")
                    Text("地图版本：${when (state.edition) { "java" -> "Java 版"; "bedrock" -> "基岩版"; else -> "待识别" }}")
                    if (state.edition == "java") Text("支持区域告示牌、可识别的命令方块显示文本和语言文件；其他区域文本会保留原文。导出为 Java 地图 ZIP。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("原文语言（可多选）", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("en" to "英语", "ru" to "俄语", "de" to "德语", "fr" to "法语", "es" to "西班牙语").forEach { (value, label) ->
                            FilterChip(selected = value in state.sourceLanguages.ifEmpty { setOf(state.language) }, onClick = { model.language(value) }, enabled = !state.busy && (state.entries.isEmpty() || state.activeOperation == "extract"), label = { Text(label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = model::resumeExtraction,
                            enabled = !state.busy && (state.entries.isEmpty() || state.activeOperation == "extract" && state.stage in setOf("任务已暂停", "任务中断，可重新处理", "任务失败"))) {
                            Text(if (state.activeOperation == "extract") "继续提取" else "提取文字")
                        }
                    }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { importTable.launch(arrayOf("*/*")) }, enabled = !state.busy && state.entries.isNotEmpty()) { Text("导入翻译表") }
                        OutlinedButton(onClick = { exportTable.launch("Minecraft翻译表.json") },
                            enabled = !state.busy && state.entries.isNotEmpty()) { Text("导出翻译表") }
                        Button(onClick = {
                            val base = state.sourceName.substringBeforeLast('.').ifBlank { "Minecraft地图" }
                                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(100)
                            exportWorld.launch("$base-简体中文.${if (state.edition == "java") "zip" else "mcworld"}")
                        }, enabled = !state.busy && state.entries.isNotEmpty() &&
                            !(state.activeOperation == "extract" && state.stage != "提取完成")) { Text("验证并导出地图") }
                    }
                    Text("翻译表管理", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            OutlinedButton(onClick = { batchMenu = true }, enabled = !state.busy) { Text("每批 $batchSize 条") }
                            DropdownMenu(expanded = batchMenu, onDismissRequest = { batchMenu = false }) {
                                listOf(100, 250, 500, 1000).forEach { value ->
                                    DropdownMenuItem(text = { Text("$value 条") }, onClick = { batchSize = value; batchMenu = false })
                                }
                                DropdownMenuItem(text = { Text("自定义") }, onClick = { batchMenu = false; customBatch = batchSize.toString() })
                            }
                        }
                        OutlinedButton(onClick = { exportBatches.launch("Minecraft翻译表-分批.zip") }, enabled = !state.busy && state.entries.isNotEmpty()) { Text("分批导出翻译表") }
                    }
                    if (customBatch.isNotEmpty()) {
                        OutlinedTextField(value = customBatch, onValueChange = { value ->
                            customBatch = value.filter(Char::isDigit).take(4)
                            customBatch.toIntOrNull()?.takeIf { it in 1..1000 }?.let { batchSize = it }
                        }, label = { Text("自定义每批记录数（1–1000）") }, singleLine = true)
                    }
                    OutlinedButton(onClick = { importBatches.launch(arrayOf("*/*")) }, enabled = !state.busy && state.entries.isNotEmpty()) { Text("批量导入翻译表") }
                    OutlinedButton(onClick = { model.enqueue("table-check") }, enabled = !state.busy && state.entries.isNotEmpty()) { Text("检查翻译表") }
                    if (state.stage == "批量导入预览" && !state.busy) {
                        Text(state.progress)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { model.batchImport(emptyList(), "keep") }) { Text("保留当前译文") }
                            OutlinedButton(onClick = { model.batchImport(emptyList(), "replace") }) { Text("使用新译文") }
                            OutlinedButton(onClick = { model.batchImport(emptyList(), "valid-only") }) { Text("仅导入有效记录") }
                            OutlinedButton(onClick = { model.batchImport(emptyList(), "custom") }) { Text("按逐条选择导入") }
                        }
                    }
                    Text("待处理、失败和选择保留的项目会原样导出；不会标记为翻译成功。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.stage == "批量导入预览") items(state.importConflicts, key = { "conflict-${it.id}" }) { conflict ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("冲突：${conflict.original}")
                        Text("当前：${conflict.current}")
                        Text("新译文：${conflict.incoming}")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = state.conflictChoices[conflict.id] != true,
                                onClick = { model.chooseConflict(conflict.id, false) }, label = { Text("保留当前") })
                            FilterChip(selected = state.conflictChoices[conflict.id] == true,
                                onClick = { model.chooseConflict(conflict.id, true) }, label = { Text("使用新译文") })
                        }
                    }
                }
            }
            if (state.export.isNotEmpty() && !state.busy) item {
                Button(onClick = {
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.translatedworlds", File(state.export))
                        val intent = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, "分享汉化地图"))
                    }.onFailure { model.message.value = "无法分享：${it.message}" }
                }) { Text("分享已验证的 ${if (state.edition == "java") "Java 地图 ZIP" else "基岩版 .mcworld"}") }
            }
            item {
                val successful = state.entries.count { it.status in setOf("自动翻译", "手动修改") }
                val failed = state.entries.count { it.status == "失败" }
                Text("共 ${state.entries.size} 项 · 有译文 $successful · 失败 $failed · 保留记录 ${state.issues.size}")
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("搜索原文、译文或来源") }, modifier = Modifier.fillMaxWidth())
                Row {
                    FilterChip(selected = filterFailures, onClick = { filterFailures = !filterFailures }, label = { Text("仅失败 / 待处理") })
                    TextButton(onClick = { showIssues = !showIssues }) { Text(if (showIssues) "隐藏保留报告" else "查看保留报告") }
                }
            }
            if (showIssues) items(state.issues) { issue -> Text(issue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            items(state.entries.filter { entry ->
                (!filterFailures || entry.status in setOf("失败", "待处理")) &&
                    (search.isBlank() || entry.original.contains(search, true) || entry.translation.contains(search, true) || entry.location.contains(search, true))
            }, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.location, style = MaterialTheme.typography.labelSmall)
                        Text("原文：${entry.original}")
                        Text("译文：${entry.translation.ifBlank { "（保留原文）" }}", color = MaterialTheme.colorScheme.primary)
                        Text("${entry.status}${if (entry.reason.isBlank()) "" else " · ${entry.reason}"}", style = MaterialTheme.typography.bodySmall,
                            color = if (entry.status == "失败") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { selected = entry; draft = entry.translation.ifBlank { entry.original } }, enabled = !state.busy) { Text("编辑") }
                            TextButton(onClick = { model.keep(entry.id) }, enabled = !state.busy) { Text("保留原文") }
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = model::cleanup, enabled = !state.busy) { Text("清理当前工作副本") } }
        }
    }
    selected?.let { entry ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("对照编辑") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.original)
                OutlinedTextField(draft, { draft = it }, label = { Text("简体中文译文") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            } },
            confirmButton = { TextButton(onClick = { model.edit(entry.id, draft); selected = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } })
    }
}

@Composable
private fun rememberSaveableState(): MutableState<String> = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

