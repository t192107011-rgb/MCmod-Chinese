package cn.mcworld.translator

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.io.File
import cn.mcworld.translator.core.SafeWorldArchive

class WorldViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("project", 0)
    private var projectId = preferences.getString("active", null) ?: UUID.randomUUID().toString()
    private var store = ProjectStore(application, projectId)
    private val mutable = MutableStateFlow(store.read())
    val state = mutable.asStateFlow()
    val message = MutableStateFlow("")
    private val workManager = WorkManager.getInstance(application)
    init {
        preferences.edit().putString("active", projectId).apply()
        viewModelScope.launch(Dispatchers.IO) {
            // Recover a persisted interrupted task once, before accepting new work.
            // Polling must not clear busy during WorkManager's asynchronous enqueue.
            val initialStore = store
            val initialId = projectId
            val initial = initialStore.read()
            if (initial.world.isNotEmpty() && initial.edition.isBlank()) {
                runCatching { SafeWorldArchive().edition(File(initial.world)).name.lowercase() }.getOrNull()?.let {
                    initial.edition = it
                    initialStore.save(initial)
                }
            }
            if (initial.busy) {
                val info = workManager.getWorkInfosForUniqueWork("world-$initialId").get()
                if (info.none { !it.state.isFinished }) {
                    initial.busy = false
                    initial.stage = "任务中断，可重新处理"
                    if (initial.activeOperation == "extract") {
                        initial.progressCurrent = 0
                        initial.progressTotal = 0
                        initial.progress = if (initial.edition == "java")
                            "已完成 ${initial.javaDone.size} 个 Java 区域文件；可继续提取"
                        else "已完成 ${initial.extractedRecords.size} / 4 个数据库区间；可继续提取"
                        initialStore.clearProgress()
                    }
                    initialStore.save(initial)
                }
            }
            var observedStore = initialStore
            var observedVersion = -1L
            var observedProgress = -1L
            while (true) {
                val activeStore = store
                val version = activeStore.stateVersion()
                if (activeStore !== observedStore || version != observedVersion) {
                    mutable.value = activeStore.read()
                    observedStore = activeStore
                    observedVersion = version
                }
                val progressVersion = activeStore.progressVersion()
                if (progressVersion != observedProgress) {
                    activeStore.readProgress()?.let { (text, current, total) ->
                        mutable.value = mutable.value.copy(progress = text, progressCurrent = current, progressTotal = total)
                    }
                    observedProgress = progressVersion
                }
                delay(750)
            }
        }
    }
    fun import(uri: Uri, folder: Boolean) {
        if (mutable.value.busy) return
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            projectId = UUID.randomUUID().toString(); store = ProjectStore(getApplication(), projectId)
            preferences.edit().putString("active", projectId).apply()
            val name = if (folder) DocumentFile.fromTreeUri(getApplication(), uri)?.name
                else DocumentFile.fromSingleUri(getApplication(), uri)?.name
            store.save(ProjectState(projectId, sourceName = name.orEmpty())); mutable.value = store.read()
            enqueue("import", "source" to uri.toString(), "folder" to folder)
        } catch (failure: Exception) { message.value = "无法保留访问权限：${failure.message}" }
    }
    fun enqueue(operation: String, vararg extra: Pair<String, Any>) {
        if (mutable.value.busy) return
        val data = workDataOf("project" to projectId, "operation" to operation, *extra)
        val state = store.read()
        state.busy = true
        state.activeOperation = operation
        state.stage = "任务排队中"
        state.progress = if (operation == "extract" && (state.extractedRecords.isNotEmpty() || state.javaDone.isNotEmpty()))
            "准备继续提取，已完成部分将保留" else "准备开始：${operationLabel(operation)}"
        state.progressCurrent = 0
        state.progressTotal = 0
        store.clearProgress()
        store.save(state); mutable.value = state
        workManager.enqueueUniqueWork("world-$projectId", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WorldWorker>().setInputData(data).build())
    }
    fun cancel() { pause() }
    private fun operationLabel(operation: String) = when (operation) {
        "import" -> "导入地图"; "extract" -> "提取文字"; "export" -> "验证并导出地图"
        "table-import", "batch-import" -> "导入翻译表"
        "table-export", "batch-export" -> "导出翻译表"
        "table-check" -> "检查翻译表"; else -> "处理任务"
    }
    val reuseEnabled = MutableStateFlow(preferences.getBoolean("reuse", true))
    val chineseEnabled = MutableStateFlow(preferences.getBoolean("chinese", true))
    fun setReuse(value: Boolean) { preferences.edit().putBoolean("reuse", value).apply(); reuseEnabled.value = value }
    fun setChinese(value: Boolean) { preferences.edit().putBoolean("chinese", value).apply(); chineseEnabled.value = value }
    fun pause() {
        val currentId = projectId
        val currentStore = store
        workManager.cancelUniqueWork("world-$currentId")
        viewModelScope.launch(Dispatchers.IO) {
            repeat(120) {
                val active = workManager.getWorkInfosForUniqueWork("world-$currentId").get().any { !it.state.isFinished }
                if (!active) {
                    val state = currentStore.read()
                    if (state.busy) {
                        state.busy = false
                        state.stage = "任务已暂停"
                        if (state.activeOperation == "extract") {
                            currentStore.clearProgress()
                            state.progressCurrent = 0
                            state.progressTotal = 0
                            state.progress = if (state.edition == "java")
                                "已完成 ${state.javaDone.size} 个 Java 区域文件；可继续提取"
                            else "已完成 ${state.extractedRecords.size} / 4 个数据库区间；可继续提取"
                        }
                        currentStore.save(state)
                    }
                    return@launch
                }
                delay(500)
            }
        }
    }
    fun resumeExtraction() = enqueue("extract")
    fun language(value: String) { update { state ->
        require(value in setOf("en", "ru", "de", "fr", "es"))
        val chosen = state.sourceLanguages
        if (chosen.isEmpty()) chosen += state.language
        if (value in chosen) {
            if (chosen.size > 1) chosen.remove(value)
        } else chosen += value
        state.language = chosen.first()
        if (state.activeOperation == "extract") {
            state.resourceDone.clear()
            state.entries.removeAll { it.file.isNotEmpty() }
            state.issues.removeAll { !it.startsWith("db/") }
            state.stage = "任务已暂停"
        }
    } }
    fun edit(id: String, value: String) { update { state ->
        val entry = state.entries.single { it.id == id }
        entry.translation = value; entry.status = "手动修改"; entry.reason = "待导出校验"
    } }
    fun keep(id: String) { update { state -> state.entries.single { it.id == id }.apply { translation = ""; status = "保留原文"; reason = "用户选择" } } }
    private fun update(change: (ProjectState) -> Unit) {
        if (mutable.value.busy) return
        try { val state = store.read(); change(state); store.save(state); mutable.value = state }
        catch (failure: Exception) { message.value = failure.message ?: "无法保存" }
    }
    fun table(uri: Uri, importing: Boolean) {
        enqueue(if (importing) "table-import" else "table-export", "tableUri" to uri.toString())
    }
    fun batchExport(uri: Uri, batchSize: Int) {
        enqueue("batch-export", "tableUri" to uri.toString(), "batchSize" to batchSize)
    }
    private var pendingBatchUris: Array<String> = preferences.getString("pendingBatches", "")!!.split("\n").filter(String::isNotBlank).toTypedArray()
    fun batchImport(uris: List<Uri>, resolution: String = "preview") {
        if (resolution == "preview") {
            uris.forEach { uri ->
                runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            pendingBatchUris = uris.map(Uri::toString).toTypedArray()
            preferences.edit().putString("pendingBatches", pendingBatchUris.joinToString("\n")).apply()
        }
        val sources = pendingBatchUris
        if (sources.isEmpty()) return
        enqueue("batch-import", "tableUris" to sources, "resolution" to resolution)
    }
    fun chooseConflict(id: String, useIncoming: Boolean) {
        update { state ->
            require(state.importConflicts.any { it.id == id })
            state.conflictChoices[id] = useIncoming
        }
    }
    fun cleanup() {
        if (mutable.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            File(getApplication<Application>().cacheDir, "world-exports").listFiles()?.filter { it.name.contains(projectId) }?.forEach { it.delete() }
            store.directory.deleteRecursively()
            projectId = UUID.randomUUID().toString(); store = ProjectStore(getApplication(), projectId)
            preferences.edit().putString("active", projectId).apply()
            mutable.value = store.read()
            message.value = "已清理当前工作副本；原地图未修改"
        }
    }
}

