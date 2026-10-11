package cn.mcworld.translator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

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
    var export: String = "") {
    fun json() = JSONObject().put("id", id).put("world", world).put("language", language)
        .put("stage", stage).put("progress", progress).put("busy", busy).put("export", export)
        .put("entries", JSONArray(entries.map { it.json() })).put("issues", JSONArray(issues))
    companion object {
        fun from(j: JSONObject): ProjectState {
            val entries = j.getJSONArray("entries"); val issues = j.getJSONArray("issues")
            return ProjectState(j.getString("id"), j.getString("world"), j.getString("language"), j.getString("stage"),
                j.getString("progress"), j.getBoolean("busy"),
                (0 until entries.length()).map { TextEntry.from(entries.getJSONObject(it)) }.toMutableList(),
                (0 until issues.length()).map { issues.getString(it) }.toMutableList(), j.getString("export"))
        }
    }
}

class ProjectStore(context: Context, val id: String) {
    init { require(id.matches(Regex("[0-9a-f-]{36}"))) }
    val directory = File(context.filesDir, "projects/$id").apply { mkdirs() }
    private val stateFile = File(directory, "project.json")
    fun read(): ProjectState = synchronized(lock) {
        if (!stateFile.exists()) ProjectState(id) else ProjectState.from(JSONObject(stateFile.readText()))
    }
    fun save(state: ProjectState) = synchronized(lock) {
        val temporary = File(directory, "project.json.tmp")
        temporary.outputStream().use { it.write(state.json().toString().toByteArray()); it.flush() }
        require(temporary.renameTo(stateFile)) { "无法保存审阅数据" }
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
