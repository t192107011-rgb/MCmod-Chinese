package cn.mcworld.translator.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/** SAF imports into private storage. No write permission on the original is needed. */
class SafWorkingCopy(private val context: Context) {
    data class Limits(val bytes: Long = 2L * 1024 * 1024 * 1024, val files: Int = 20_000, val depth: Int = 32)

    suspend fun copyArchive(uri: Uri, limit: Long = 2L * 1024 * 1024 * 1024, progress: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val work = File(context.cacheDir, "world-import-${UUID.randomUUID()}.zip")
        require(work.createNewFile())
        try {
            val source = context.contentResolver.openInputStream(uri) ?: error("无法读取所选地图；访问权限可能已失效")
            source.use { input ->
                work.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutine.ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        require(total <= limit) { "地图超过工作副本大小限制" }
                        output.write(buffer, 0, size)
                        progress(total)
                    }
                }
            }
            work
        } catch (failure: Throwable) { work.delete(); throw failure }
    }

    suspend fun copyFolder(uri: Uri, limits: Limits = Limits(), progress: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val root = DocumentFile.fromTreeUri(context, uri) ?: error("无法访问地图文件夹")
        require(root.isDirectory && root.canRead()) { "所选文件夹不可读" }
        val work = File(context.cacheDir, "world-import-${UUID.randomUUID()}")
        require(work.mkdir())
        var total = 0L
        var count = 0
        val visited = mutableSetOf<Uri>()
        fun visit(source: DocumentFile, target: File, depth: Int) {
            coroutine.ensureActive()
            require(depth <= limits.depth && visited.add(source.uri)) { "文件夹层级过深或包含循环" }
            val names = mutableSetOf<String>()
            for (child in source.listFiles()) {
                coroutine.ensureActive()
                require(++count <= limits.files) { "文件数超过限制" }
                val name = child.name ?: error("文件名称不可读")
                require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 }) { "不安全的文件名" }
                require(names.add(java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).lowercase(Locale.ROOT))) { "重复文件名" }
                val destination = File(target, name)
                require(child.canRead()) { "文件访问权限已失效" }
                when {
                    child.isDirectory -> { require(destination.mkdir()); visit(child, destination, depth + 1) }
                    child.isFile -> {
                        require(destination.createNewFile())
                        (context.contentResolver.openInputStream(child.uri) ?: error("无法读取文件")).use { input ->
                            destination.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    coroutine.ensureActive()
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    total += size
                                    require(total <= limits.bytes) { "地图超过大小限制" }
                                    output.write(buffer, 0, size)
                                    progress(total)
                                }
                            }
                        }
                    }
                    else -> error("不支持的文档类型")
                }
            }
        }
        try { visit(root, work, 0); work }
        catch (failure: Throwable) { work.deleteRecursively(); throw failure }
    }

    /** New SAF document only; caller must use ACTION_CREATE_DOCUMENT and retain its URI. */
    suspend fun exportToNewDocument(archive: File, uri: Uri, progress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val document = DocumentFile.fromSingleUri(context, uri) ?: error("导出文档不可用")
        require(document.isFile && document.length() == 0L && document.canWrite()) { "导出目标必须是新建的空文档" }
        try {
            archive.inputStream().use { input ->
                (context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入导出文档")).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutine.ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                        total += size
                        progress(total)
                    }
                }
            }
        } catch (failure: Throwable) {
            // Only this new export is eligible for cleanup; never delete the input URI.
            runCatching { document.delete() }
            throw failure
        }
    }
}
