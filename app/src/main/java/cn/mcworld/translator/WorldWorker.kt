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
            state.stage = when (operation) {
                "import" -> "导入和验证中"
                "extract" -> "提取中"
                "export" -> "写回及导出验证中"
                "table-import" -> "翻译表导入中"
                "table-export" -> "翻译表导出中"
                "batch-import" -> "批量导入中"
                "batch-export" -> "分批导出中"
                "table-check" -> "检查翻译表中"
                else -> error("不支持的任务")
            }
            state.progress = when (operation) {
                "import" -> "正在复制并验证地图文件"
                "extract" -> "正在准备提取；已完成的部分会保留"
                "export" -> "正在验证译文并制作地图文件"
                "table-import", "batch-import" -> "正在读取和校验翻译表"
                "table-export", "batch-export" -> "正在写入翻译表"
                else -> "正在检查翻译表"
            }
            state.progressCurrent = 0
            state.progressTotal = 0
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
                    state.edition = SafeWorldArchive().edition(File(state.world)).name.lowercase()
                    state.stage = "地图验证通过"; state.progress = "${if (state.edition == "java") "Java版" else "基岩版"}地图；原地图保持不变，请选择原文语言并提取文字"
                }
                "extract" -> WorldOperations(applicationContext, store).extract(state)
                "table-import", "table-export" -> WorldOperations(applicationContext, store).table(state,
                    Uri.parse(requireNotNull(inputData.getString("tableUri"))), operation == "table-import")
                "table-check" -> WorldOperations(applicationContext, store).checkTable(state)
                "batch-export" -> WorldOperations(applicationContext, store).batchExport(state,
                    Uri.parse(requireNotNull(inputData.getString("tableUri"))), inputData.getInt("batchSize", 500))
                "batch-import" -> WorldOperations(applicationContext, store).batchImport(state,
                    requireNotNull(inputData.getStringArray("tableUris")).map(Uri::parse),
                    inputData.getString("resolution") ?: "preview")
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
            state.stage = if (operation == "extract") "任务已暂停" else "任务已取消"
            state.progress = if (operation == "extract") {
                store.clearProgress()
                state.progressCurrent = 0
                state.progressTotal = 0
                if (state.edition == "java") "已完成 ${state.javaDone.size} 个 Java 区域文件；可继续提取"
                else "已完成 ${state.extractedRecords.size} / 4 个数据库区间；可继续提取"
            } else "原地图未修改，已完成的译文可继续审阅"
            store.save(state); throw cancelled
        } catch (failure: Exception) {
            if (operation == "import") temporaryImports.forEach { it.deleteRecursively() }
            if (operation == "extract") { store.clearProgress(); state.progressCurrent = 0; state.progressTotal = 0 }
            state.busy = false; state.stage = "任务失败"; state.progress = failure.message ?: "处理失败"
            store.save(state); Result.failure()
        }
    }
}

