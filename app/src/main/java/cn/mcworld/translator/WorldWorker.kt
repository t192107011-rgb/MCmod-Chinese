package cn.mcworld.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import cn.mcworld.translator.core.SafeWorldArchive
import cn.mcworld.translator.storage.SafWorkingCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

class WorldWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val projectId = inputData.getString("project") ?: return@withContext Result.failure()
        val store = ProjectStore(applicationContext, projectId)
        val state = store.read()
        state.busy = true
        val operation = inputData.getString("operation") ?: return@withContext Result.failure()
        val temporaryImports = mutableListOf<File>()
        try {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("world-processing", "地图处理", NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(applicationContext, "world-processing")
                .setContentTitle("Minecraft 地图汉化器").setContentText("正在处理工作副本；可在应用中取消")
                .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
            setForeground(if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(projectId.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else ForegroundInfo(projectId.hashCode(), notification))
            state.stage = when (operation) { "import" -> "导入和验证中"; "extract" -> "提取中"; "translate" -> "离线翻译中"; "export" -> "写回及导出验证中"; "table-import" -> "翻译表导入中"; "table-export" -> "翻译表导出中"; else -> error("不支持的任务") }
            store.save(state)
            when (operation) {
                "import" -> {
                    val source = Uri.parse(requireNotNull(inputData.getString("source")))
                    val saf = SafWorkingCopy(applicationContext)
                    var last = 0L
                    fun progress(bytes: Long) {
                        if (bytes - last >= 1024 * 1024) { state.progress = "已复制 ${bytes / 1024 / 1024} MiB"; store.save(state); last = bytes }
                    }
                    if (inputData.getBoolean("folder", false)) {
                        val copied = saf.copyFolder(source, progress = ::progress)
                        try {
                            val container = File(store.directory, "import-${System.nanoTime()}")
                            temporaryImports += container
                            require(copied.renameTo(container)) { "无法保存工作副本" }
                            state.world = SafeWorldArchive().worldRoot(container).absolutePath
                        } finally { copied.deleteRecursively() }
                    } else {
                        val archive = saf.copyArchive(source, progress = ::progress)
                        try {
                            val coroutine = currentCoroutineContext()
                            val container = File(store.directory, "import-${System.nanoTime()}")
                            temporaryImports += container
                            state.world = SafeWorldArchive().extract(archive, container) { coroutine.ensureActive() }.absolutePath
                        } finally { archive.delete() }
                    }
                    state.stage = "地图验证通过"; state.progress = "原地图保持不变，请选择原文语言并提取文字"
                }
                "extract" -> WorldOperations(applicationContext, store).extract(state)
                "table-import", "table-export" -> WorldOperations(applicationContext, store).table(state,
                    Uri.parse(requireNotNull(inputData.getString("tableUri"))), operation == "table-import")
                "translate" -> {
                    val selected = inputData.getString("entry")
                    val entries = state.entries.filter { (selected == null || it.id == selected) && it.status !in setOf("保留原文", "手动修改") }
                    OfflineTranslator(applicationContext).use { engine ->
                        for ((index, entry) in entries.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            try {
                                val resourceLanguage = when (entry.file.substringAfterLast('/').substringBeforeLast('.')) {
                                    "en_US", "en_GB" -> "en"
                                    "ru_RU" -> "ru"
                                    "de_DE" -> "de"
                                    "fr_FR" -> "fr"
                                    "es_ES" -> "es"
                                    else -> state.language
                                }
                                val translated = engine.translate(entry.original, resourceLanguage)
                                if (translated == entry.original) {
                                    entry.translation = ""; entry.status = "保留原文"; entry.reason = "没有可翻译的自然语言"
                                } else {
                                    entry.translation = translated; entry.status = "自动翻译"; entry.reason = ""
                                    WorldOperations(applicationContext, store).validate(entry)
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { entry.translation = ""; entry.status = "失败"; entry.reason = failure.message ?: "推理失败" }
                            state.progress = "${index + 1}/${entries.size}；失败项目保留原文"
                            store.save(state)
                        }
                    }
                    state.stage = "翻译结束，等待审阅"
                }
                "export" -> {
                    val archive = WorldOperations(applicationContext, store).export(state)
                    val uri = inputData.getString("destination")
                    if (uri != null) SafWorkingCopy(applicationContext).exportToNewDocument(archive, Uri.parse(uri))
                }
            }
            state.busy = false; state.activeOperation = ""; store.save(state)
            Result.success()
        } catch (cancelled: CancellationException) {
            if (operation == "import") temporaryImports.forEach { it.deleteRecursively() }
            state.busy = false
            state.stage = if (operation in setOf("translate", "extract")) "任务已暂停" else "任务已取消"
            state.progress = if (operation == "extract") {
                store.clearProgress()
                state.progressCurrent = state.extractedRecords.values.sum()
                "已完成 ${state.extractedRecords.size} / 4 个数据库区间；可继续提取"
            } else "原地图未修改，已完成的译文可继续审阅"
            store.save(state); throw cancelled
        } catch (failure: Exception) {
            if (operation == "import") temporaryImports.forEach { it.deleteRecursively() }
            if (operation == "extract") { store.clearProgress(); state.progressCurrent = state.extractedRecords.values.sum() }
            state.busy = false; state.stage = "任务失败"; state.progress = failure.message ?: "处理失败"
            store.save(state); Result.failure()
        }
    }
}
