package cn.mcworld.translator

import android.app.Application
import android.content.Intent
import android.net.Uri
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
            if (initial.busy) {
                val info = workManager.getWorkInfosForUniqueWork("world-$initialId").get()
                if (info.none { !it.state.isFinished }) {
                    initial.busy = false
                    initial.stage = "任务中断，可重新处理"
                    initialStore.save(initial)
                }
            }
            while (true) {
                val state = store.read()
                mutable.value = state
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
            store.save(ProjectState(projectId)); mutable.value = store.read()
            enqueue("import", "source" to uri.toString(), "folder" to folder)
        } catch (failure: Exception) { message.value = "无法保留访问权限：${failure.message}" }
    }
    fun enqueue(operation: String, vararg extra: Pair<String, Any>) {
        if (mutable.value.busy) return
        val data = workDataOf("project" to projectId, "operation" to operation, *extra)
        val state = store.read(); state.busy = true; store.save(state); mutable.value = state
        workManager.enqueueUniqueWork("world-$projectId", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WorldWorker>().setInputData(data).build())
    }
    fun cancel() { workManager.cancelUniqueWork("world-$projectId") }
    fun language(value: String) { update { it.language = value } }
    fun edit(id: String, value: String) { update { state ->
        val entry = state.entries.single { it.id == id }
        entry.translation = value; entry.status = "手动修改"; entry.reason = "待导出校验"
    } }
    fun keep(id: String) { update { state -> state.entries.single { it.id == id }.apply { translation = ""; status = "保留原文"; reason = "用户选择" } } }
    fun retranslate(id: String) {
        update { state -> state.entries.single { it.id == id }.apply { status = "待处理"; translation = ""; reason = "" } }
        enqueue("translate", "entry" to id)
    }
    private fun update(change: (ProjectState) -> Unit) {
        if (mutable.value.busy) return
        try { val state = store.read(); change(state); store.save(state); mutable.value = state }
        catch (failure: Exception) { message.value = failure.message ?: "无法保存" }
    }
    fun table(uri: Uri, importing: Boolean) {
        enqueue(if (importing) "table-import" else "table-export", "tableUri" to uri.toString())
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
