package cn.mcworld.translator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken

data class TextEntry(
    val id: String, val location: String, val original: String,
    var translation: String = "", var status: String = "待处理", var reason: String = "",
    val recordKey: String = "", val path: List<String> = emptyList(),
    val file: String = "", val start: Int = 0, val end: Int = 0,
    val mode: String = "text", val span: Int = -1,
) {
    fun json() = JSONObject().put("id", id).put("location", location).put("original", original)
        .put("translation", translation).put("status", status).put("reason", reason)
        .put("recordKey", recordKey).put("path", JSONArray(path)).put("file", file)
        .put("start", start).put("end", end).put("mode", mode).put("span", span)
    companion object {
        fun from(j: JSONObject): TextEntry {
            val p = j.getJSONArray("path")
            return TextEntry(j.getString("id"), j.getString("location"), j.getString("original"),
                j.getString("translation"), j.getString("status"), j.getString("reason"), j.getString("recordKey"),
                (0 until p.length()).map { p.getString(it) }, j.getString("file"), j.getInt("start"), j.getInt("end"), j.getString("mode"), j.getInt("span"))
        }
    }
}

data class ProjectState(val id: String, var world: String = "", var language: String = "en",
    var stage: String = "等待导入", var progress: String = "", var busy: Boolean = false,
    val entries: MutableList<TextEntry> = mutableListOf(), val issues: MutableList<String> = mutableListOf(),
    var export: String = "", val conflictChoices: MutableMap<String, Boolean> = mutableMapOf(),
    val importConflicts: MutableList<ImportConflict> = mutableListOf(),
    var sourceName: String = "", var progressCurrent: Long = 0, var progressTotal: Long = 0,
    val extractedRecords: MutableMap<Int, Long> = mutableMapOf(), var activeOperation: String = "",
    val resourceDone: MutableSet<String> = mutableSetOf(),
    val sourceLanguages: MutableSet<String> = mutableSetOf(), var edition: String = "",
    val javaDone: MutableSet<String> = mutableSetOf()) {
    fun metadata() = JSONObject().put("id", id).put("world", world).put("language", language)
        .put("stage", stage).put("progress", progress).put("busy", busy).put("export", export)
        .put("issues", JSONArray(issues))
        .put("conflictChoices", JSONObject(conflictChoices as Map<*, *>))
        .put("importConflicts", JSONArray(importConflicts.map { it.json() }))
        .put("sourceName", sourceName).put("progressCurrent", progressCurrent).put("progressTotal", progressTotal)
        .put("extractedRecords", JSONObject(extractedRecords.mapKeys { it.key.toString() }))
        .put("activeOperation", activeOperation).put("resourceDone", JSONArray(resourceDone.toList()))
        .put("sourceLanguages", JSONArray(sourceLanguages.toList()))
        .put("edition", edition).put("javaDone", JSONArray(javaDone.toList()))
    fun json() = metadata().put("entries", JSONArray(entries.map { it.json() }))
    companion object {
        fun from(j: JSONObject, loadedEntries: MutableList<TextEntry>? = null): ProjectState {
            val entries = j.optJSONArray("entries"); val issues = j.getJSONArray("issues")
            return ProjectState(j.getString("id"), j.getString("world"), j.getString("language"), j.getString("stage"),
                j.getString("progress"), j.getBoolean("busy"),
                loadedEntries ?: (0 until (entries?.length() ?: 0)).map { TextEntry.from(entries!!.getJSONObject(it)) }.toMutableList(),
                (0 until issues.length()).map { issues.getString(it) }.toMutableList(), j.getString("export"),
                j.optJSONObject("conflictChoices")?.let { choices -> choices.keys().asSequence().associateWith { choices.getBoolean(it) }.toMutableMap() } ?: mutableMapOf(),
                j.optJSONArray("importConflicts")?.let { conflicts -> (0 until conflicts.length()).map { ImportConflict.from(conflicts.getJSONObject(it)) }.toMutableList() } ?: mutableListOf(),
                j.optString("sourceName", ""), j.optLong("progressCurrent", 0), j.optLong("progressTotal", 0),
                j.optJSONObject("extractedRecords")?.let { completed -> completed.keys().asSequence().associate { it.toInt() to completed.getLong(it) }.toMutableMap() } ?: mutableMapOf(),
                j.optString("activeOperation", ""),
                j.optJSONArray("resourceDone")?.let { done -> (0 until done.length()).map { done.getString(it) }.toMutableSet() } ?: mutableSetOf(),
                j.optJSONArray("sourceLanguages")?.let { selected -> (0 until selected.length()).map { selected.getString(it) }.toMutableSet() } ?: mutableSetOf(),
                j.optString("edition", ""),
                j.optJSONArray("javaDone")?.let { done -> (0 until done.length()).map { done.getString(it) }.toMutableSet() } ?: mutableSetOf())
        }
    }
}

data class ImportConflict(val id: String, val original: String, val current: String, val incoming: String) {
    fun json() = JSONObject().put("id", id).put("original", original).put("current", current).put("incoming", incoming)
    companion object { fun from(j: JSONObject) = ImportConflict(j.getString("id"), j.getString("original"), j.getString("current"), j.getString("incoming")) }
}

class ProjectStore(context: Context, val id: String) {
    init { require(id.matches(Regex("[0-9a-f-]{36}"))) }
    val directory = File(context.filesDir, "projects/$id").apply { mkdirs() }
    private val stateFile = File(directory, "project.json")
    private val atomicState = AtomicFile(stateFile)
    fun stateVersion(): Long = stateFile.lastModified()
    private val progressFile = File(directory, "progress.json")
    private val atomicProgress = AtomicFile(progressFile)
    fun progressVersion(): Long = progressFile.lastModified()
    fun readProgress(): Triple<String, Long, Long>? = runCatching {
        val value = JSONObject(atomicProgress.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        Triple(value.getString("text"), value.getLong("current"), value.getLong("total"))
    }.getOrNull()
    fun writeProgress(text: String, current: Long, total: Long) {
        val output = atomicProgress.startWrite()
        try {
            output.write(JSONObject().put("text", text).put("current", current).put("total", total).toString().toByteArray(Charsets.UTF_8))
            atomicProgress.finishWrite(output)
        } catch (failure: Throwable) { atomicProgress.failWrite(output); throw failure }
    }
    fun clearProgress() { atomicProgress.delete() }
    private fun readValue(reader: JsonReader): Any = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().also { obj ->
            reader.beginObject()
            while (reader.hasNext()) obj.put(reader.nextName(), readValue(reader))
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> JSONArray().also { array ->
            reader.beginArray()
            while (reader.hasNext()) array.put(readValue(reader))
            reader.endArray()
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble() }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
        else -> error("Invalid project JSON")
    }
    fun read(): ProjectState = synchronized(lock) {
        if (!stateFile.exists() && !File(directory, "project.json.bak").exists()) ProjectState(id)
        else atomicState.openRead().bufferedReader(Charsets.UTF_8).use { source ->
            JsonReader(source).use { reader ->
                val metadata = JSONObject()
                val entries = mutableListOf<TextEntry>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name == "entries") {
                        reader.beginArray()
                        while (reader.hasNext()) entries += TextEntry.from(readValue(reader) as JSONObject)
                        reader.endArray()
                    } else metadata.put(name, readValue(reader))
                }
                reader.endObject()
                ProjectState.from(metadata, entries)
            }
        }
    }
    fun save(state: ProjectState) = synchronized(lock) {
        val output = atomicState.startWrite()
        try {
            val writer = output.bufferedWriter(Charsets.UTF_8)
            writer.write(state.metadata().toString().dropLast(1))
            writer.write(",\"entries\":[")
            state.entries.forEachIndexed { index, entry ->
                if (index > 0) writer.write(",")
                writer.write(entry.json().toString())
            }
            writer.write("]}")
            writer.flush()
            atomicState.finishWrite(output)
        } catch (failure: Throwable) { atomicState.failWrite(output); throw failure }
    }
    companion object { private val lock = Any() }
}
fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
fun shaFile(file: File): String {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
    }
    return hex(hash.digest())
}
fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val output = CharArray(bytes.size * 2)
    for (index in bytes.indices) {
        val value = bytes[index].toInt() and 255
        output[index * 2] = digits[value ushr 4]
        output[index * 2 + 1] = digits[value and 15]
    }
    return String(output)
}
fun unhex(text: String): ByteArray {
    require(text.length % 2 == 0 && text.matches(Regex("[0-9a-f]+")))
    return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

