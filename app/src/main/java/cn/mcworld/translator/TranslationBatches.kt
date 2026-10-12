package cn.mcworld.translator

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable batches. Indices are descriptive only; IDs and original text determine identity. */
object TranslationBatches {
    const val LIMIT = 8 * 1024 * 1024
    private const val MAX_FILES = 1000
    private const val MAX_TOTAL = 128L * 1024 * 1024
    data class Proposed(val id: String, val translation: String)
    data class Preview(val changes: List<Proposed>, val conflicts: List<Proposed>, val errors: List<String>, val skipped: Int)

    private fun bytes(document: JSONObject) = document.toString().toByteArray(Charsets.UTF_8)
    fun export(state: ProjectState, batchSize: Int, output: OutputStream, progress: (String) -> Unit = {}) {
        require(batchSize in 1..1000)
        val manifest = JSONObject().put("formatVersion", 2).put("projectId", state.id)
            .put("totalEntries", state.entries.size).put("batchSize", batchSize)
            .put("targetLanguage", "zh-CN")
        val header = JSONObject().put("id", state.id).put("world", state.world)
            .put("language", state.language).toString().dropLast(1) + ",\"entries\":["
        val prefix = header.toByteArray(Charsets.UTF_8)
        val suffix = "]}".toByteArray(Charsets.UTF_8)
        val batches = JSONArray()
        ZipOutputStream(output.buffered()).use { zip ->
            var index = 0
            var batch = 0
            while (index < state.entries.size) {
                val start = index
                val buffer = ByteArrayOutputStream()
                buffer.write(prefix)
                var count = 0
                while (index < state.entries.size && count < batchSize) {
                    val row = bytes(state.entries[index].json())
                    val separator = if (count == 0) 0 else 1
                    if (buffer.size().toLong() + separator + row.size + suffix.size > LIMIT) {
                        require(count > 0) { "单条记录超过 8 MiB" }
                        break
                    }
                    if (separator > 0) buffer.write(','.code)
                    buffer.write(row)
                    count++; index++
                }
                buffer.write(suffix)
                val data = buffer.toByteArray()
                batch++
                val name = "翻译表_%03d.json".format(batch)
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
                batches.put(JSONObject().put("file", name).put("startIndex", start)
                    .put("endIndex", index - 1).put("count", count).put("sha256", sha(data)))
                progress("已导出 $index/${state.entries.size} 项")
            }
            manifest.put("totalBatches", batch).put("batches", batches)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(bytes(manifest)); zip.closeEntry()
        }
    }

    private fun read(input: InputStream, max: Int = LIMIT): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= max) { "文件超过安全大小限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun decode(bytes: ByteArray): JSONObject {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return JSONObject(text)
    }
    fun collect(inputs: List<Pair<String, InputStream>>): Pair<List<JSONObject>, List<String>> {
        val documents = mutableListOf<JSONObject>()
        val errors = mutableListOf<String>()
        for ((filename, stream) in inputs) {
            stream.use { source ->
                try {
                    if (filename.endsWith(".zip", true)) {
                        val parts = linkedMapOf<String, ByteArray>()
                        var totalBytes = 0L
                        ZipInputStream(source.buffered()).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                require(parts.size < MAX_FILES) { "批次数量超过限制" }
                                require(!entry.isDirectory && !entry.name.contains('/') && !entry.name.contains('\\') && entry.name !in parts) { "ZIP 内存在异常文件名" }
                                val data = read(zip)
                                totalBytes += data.size
                                require(totalBytes <= MAX_TOTAL) { "ZIP 解压后超过安全大小限制" }
                                parts[entry.name] = data
                                zip.closeEntry(); entry = zip.nextEntry
                            }
                        }
                        val manifest = parts.remove("manifest.json")?.let(::decode)
                        if (manifest != null) {
                            require(manifest.getInt("formatVersion") == 2)
                            val batchList = manifest.getJSONArray("batches")
                            require(batchList.length() == manifest.getInt("totalBatches") && parts.size == batchList.length()) { "批次缺失或包含多余文件" }
                            require(batchList.length() <= MAX_FILES)
                            var count = 0
                            val staged = mutableListOf<JSONObject>()
                            val names = mutableSetOf<String>()
                            for (i in 0 until batchList.length()) {
                                val batch = batchList.getJSONObject(i)
                                val name = batch.getString("file")
                                require(names.add(name)) { "清单文件名重复" }
                                val data = parts[name] ?: error("缺少 $name")
                                require(sha(data) == batch.getString("sha256")) { "$name SHA-256 不匹配" }
                                val doc = decode(data)
                                require(doc.getString("id") == manifest.getString("projectId")) { "$name 项目不匹配" }
                                require(doc.getJSONArray("entries").length() == batch.getInt("count")) { "$name 记录数不匹配" }
                                count += batch.getInt("count")
                                staged += doc
                            }
                            require(count == manifest.getInt("totalEntries") && names == parts.keys) { "记录总数或文件清单不匹配" }
                            val ids = mutableSetOf<String>()
                            staged.forEach { doc ->
                                val rows = doc.getJSONArray("entries")
                                for (j in 0 until rows.length()) require(ids.add(rows.getJSONObject(j).getString("id"))) { "批次内记录 ID 重复" }
                            }
                            documents += staged
                        } else {
                            for ((name, data) in parts) if (name.endsWith(".json", true)) documents += decode(data)
                            else errors += "忽略 $name"
                        }
                    } else documents += decode(read(source, MAX_TOTAL.toInt()))
                } catch (failure: Exception) { errors += "$filename：${failure.message}" }
            }
        }
        return documents to errors
    }

    fun preview(state: ProjectState, documents: List<JSONObject>, initialErrors: List<String>, validate: (TextEntry) -> Unit): Preview {
        val indexed = state.entries.associateBy { it.id }
        val seen = mutableMapOf<String, String>()
        val changes = mutableListOf<Proposed>()
        val conflicts = mutableListOf<Proposed>()
        val errors = initialErrors.toMutableList()
        var skipped = 0
        for ((batchIndex, document) in documents.withIndex()) {
            try {
                if (document.has("id")) require(document.getString("id") == state.id) { "项目 ID 不匹配" }
                val rows = document.getJSONArray("entries")
                require(rows.length() <= 100_000)
                for (i in 0 until rows.length()) {
                    try {
                        val row = rows.getJSONObject(i)
                        val id = row.getString("id")
                        val entry = indexed[id] ?: error("记录 ID 不属于当前地图")
                        require(row.getString("original") == entry.original) { "原文不匹配" }
                        // Verify all structural metadata when present; older tables may omit these fields.
                        if (row.has("location")) require(row.getString("location") == entry.location) { "记录路径不匹配" }
                        if (row.has("file")) require(row.getString("file") == entry.file) { "文件路径不匹配" }
                        if (row.has("recordKey")) require(row.getString("recordKey") == entry.recordKey) { "数据库记录不匹配" }
                        if (row.has("path")) require(row.getJSONArray("path").toString() == JSONArray(entry.path).toString()) { "NBT 路径不匹配" }
                        if (row.has("start")) require(row.getInt("start") == entry.start) { "文本起点不匹配" }
                        if (row.has("end")) require(row.getInt("end") == entry.end) { "文本终点不匹配" }
                        if (row.has("mode")) require(row.getString("mode") == entry.mode) { "文本模式不匹配" }
                        if (row.has("span")) require(row.getInt("span") == entry.span) { "命令片段不匹配" }
                        val value = row.getString("translation")
                        val previous = seen[id]
                        require(previous == null || previous == value) { "不同批次的译文冲突" }
                        if (previous != null || value.isBlank()) { skipped++; continue }
                        seen[id] = value
                        validate(entry.copy(translation = value, status = "手动修改"))
                        if (value == entry.original) {
                            if (row.optString("status") != "保留原文")
                                errors += "${entry.id}：译文与原文相同，请明确标记保留原文"
                            else skipped++
                            continue
                        }
                        val proposed = Proposed(id, value)
                        if (entry.translation.isBlank()) changes += proposed
                        else if (entry.translation == value) skipped++
                        else conflicts += proposed
                    } catch (failure: Exception) { errors += "第 ${batchIndex + 1} 批第 ${i + 1} 项：${failure.message}" }
                }
            } catch (failure: Exception) { errors += "第 ${batchIndex + 1} 批：${failure.message}" }
        }
        return Preview(changes, conflicts, errors, skipped)
    }
}
