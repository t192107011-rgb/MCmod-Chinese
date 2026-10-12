package cn.mcworld.translator

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import cn.mcworld.translator.core.DisplayCommand
import cn.mcworld.translator.core.LittleEndianNbt
import cn.mcworld.translator.core.ProtectedText
import cn.mcworld.translator.core.SafeWorldArchive
import cn.mcworld.translator.engine.NativeBridge
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

class WorldOperations(private val context: Context, private val store: ProjectStore) {
    suspend fun table(state: ProjectState, uri: Uri, importing: Boolean) {
        val resolver = context.contentResolver
        if (importing) {
            val bytes = (resolver.openInputStream(uri) ?: error("翻译表访问权限已失效")).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= 8 * 1024 * 1024) { "翻译表超过 8 MiB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            DisplayCommand.jsonStrings(source)
            val rows = JSONObject(source).getJSONArray("entries")
            require(rows.length() <= 100_000)
            val indexed = state.entries.associateBy { it.id }
            val changes = linkedMapOf<String, String>()
            val seen = mutableSetOf<String>()
            for (i in 0 until rows.length()) {
                currentCoroutineContext().ensureActive()
                val row = rows.getJSONObject(i)
                val id = row.getString("id")
                require(seen.add(id)) { "翻译表包含重复项目" }
                val entry = indexed[id] ?: error("翻译表包含不属于当前地图的项目")
                require(row.getString("original") == entry.original) { "翻译表原文不匹配" }
                val text = row.getString("translation")
                if (text.isBlank()) continue
                val candidate = entry.copy(translation = text, status = "手动修改")
                validate(candidate)
                changes[id] = text
            }
            changes.forEach { (id, value) -> indexed.getValue(id).apply { translation = value; status = "手动修改"; reason = "从翻译表导入" } }
            state.stage = "翻译表导入完成"; state.progress = "已验证并导入 ${changes.size} 项译文"
        } else {
            val bytes = state.json().toString(2).toByteArray()
            require(bytes.size <= 8 * 1024 * 1024) { "翻译表超过 8 MiB；请使用分批地图" }
            val document = DocumentFile.fromSingleUri(context, uri) ?: error("翻译表文档不可用")
            require(document.length() == 0L && document.canWrite()) { "导出目标必须是新建空文档" }
            try {
                (resolver.openOutputStream(uri, "wt") ?: error("翻译表不可写")).use { output ->
                    var offset = 0
                    while (offset < bytes.size) {
                        currentCoroutineContext().ensureActive()
                        val count = minOf(64 * 1024, bytes.size - offset)
                        output.write(bytes, offset, count); offset += count
                    }
                }
            } catch (failure: Throwable) { runCatching { document.delete() }; throw failure }
            state.stage = "翻译表已导出"; state.progress = "包含原文、译文、来源与处理状态"
        }
    }
    private suspend fun forRecords(world: File, action: suspend (ByteArray, ByteArray?) -> Unit) {
        val db = NativeBridge.openDb(File(world, "db").absolutePath)
        try {
            val cursor = NativeBridge.cursor(db)
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val item = NativeBridge.next(cursor) ?: break
                    action(requireNotNull(item[0]), item[1])
                }
            } finally { NativeBridge.closeCursor(cursor) }
        } finally { NativeBridge.closeDb(db) }
    }

    suspend fun extract(state: ProjectState) {
        require(state.world.isNotEmpty())
        state.entries.clear(); state.issues.clear()
        val originals = File(store.directory, "records").apply { mkdirs() }
        fun addEntry(entry: TextEntry) {
            require(state.entries.size < 100_000) { "可审阅文本超过 100,000 项，保留超限记录" }
            state.entries.add(entry)
        }
        var records = 0
        var lastReported = 0
        var lastSaved = System.currentTimeMillis()
        val database = NativeBridge.openDb(File(state.world, "db").absolutePath)
        try {
          val cursor = NativeBridge.cursor(database)
          try {
            while (true) {
            currentCoroutineContext().ensureActive()
            val item = NativeBridge.nextTextCandidate(cursor) ?: break
            val scanned = requireNotNull(item[2])
            records += (scanned[0].toInt() and 255) or ((scanned[1].toInt() and 255) shl 8)
            val key = item[0]
            val raw = item[1]
            if (key != null) {
            val actor = key.take(11).toByteArray().contentEquals("actorprefix".toByteArray())
            val player = key.contentEquals("~local_player".toByteArray()) || key.toString(Charsets.ISO_8859_1).startsWith("player_server_")
            val chunk = key.size in listOf(9, 13) && (key.last().toInt() and 255) in listOf(0x31, 0x32)
            if (actor || player || chunk) {
                val keyHex = hex(key)
                val checkpoint = state.entries.size
                if (raw == null) state.issues += "db/$keyHex：记录超过 32 MiB，未修改"
                else try {
                    val record = LittleEndianNbt.parse(raw, maxNodes = 100_000, copyBytes = false)
                    val fields = record.texts.associateBy { it.path }
                    val before = state.entries.size
                    fun add(text: LittleEndianNbt.Text, mode: String = "text") {
                        if (text.value.isBlank()) return
                        val actualMode = if (mode == "text" && text.value.trimStart().startsWith('{')) "rawtext" else mode
                        if (actualMode in setOf("command", "rawtext")) {
                            val plan = DisplayCommand.inspect(if (actualMode == "rawtext") "tellraw @a " + text.value else text.value)
                            state.issues += plan.issues.map { "db/$keyHex/${text.path.joinToString("/")}：$it，未修改未知参数" }
                            plan.spans.forEachIndexed { index, span ->
                                addEntry(TextEntry(sha("$keyHex/${text.path}/$index".toByteArray()),
                                    "db/$keyHex/${text.path.joinToString("/")}/显示文本[$index]", span.text,
                                    recordKey = keyHex, path = text.path, mode = actualMode, span = index))
                            }
                        } else addEntry(TextEntry(sha("$keyHex/${text.path}".toByteArray()),
                            "db/$keyHex/${text.path.joinToString("/")}", text.value, recordKey = keyHex, path = text.path))
                    }
                    for (text in record.texts) {
                        val path = text.path
                        val root = path.first()
                        val id = fields[listOf(root, "id")]?.value?.substringAfter(':')
                        if (id in listOf("Sign", "HangingSign")) {
                            if (path == listOf(root, "Text") || path == listOf(root, "FrontText", "Text") || path == listOf(root, "BackText", "Text")) add(text)
                        }
                        if (id == "CommandBlock" && path == listOf(root, "Command")) add(text, "command")
                        if (id?.lowercase() == "npc" && path.last() in listOf("InterativeText", "Actions", "Dialog", "Dialogue")) {
                            state.issues += "db/$keyHex/${path.joinToString("/")}：NPC 数据库对话版本尚未验证，保留原文；支持附带 dialogue JSON"
                        }
                        val tag = path.indexOfLast { it == "tag" }
                        if (tag > 0) {
                            val item = path.take(tag)
                            val name = fields[item + "Name"]?.value
                            val tail = path.drop(tag + 1)
                            if (record.types[path.take(tag + 1)] == 10 && name in listOf("minecraft:written_book", "minecraft:writable_book") &&
                                (tail == listOf("title") || tail == listOf("author") ||
                                    tail.size == 3 && tail[0] == "pages" && tail[2] == "text" &&
                                    record.types[path.take(tag + 2)] == 9 && record.types[path.dropLast(1)] == 10)) add(text)
                        }
                    }
                    if (state.entries.size > before) File(originals, "$keyHex.nbt").writeBytes(raw)
                } catch (failure: Exception) {
                    state.entries.subList(checkpoint, state.entries.size).clear()
                    state.issues += "db/$keyHex：${failure.message}，未修改"
                }
            }
            }
            if (records - lastReported >= 500) {
                state.progress = "已检查 $records 条数据库记录，提取 ${state.entries.size} 项文字"
                lastReported = records
            }
            val now = System.currentTimeMillis()
            if (now - lastSaved >= 15000) { store.save(state); lastSaved = now }
            }
          } finally { NativeBridge.closeCursor(cursor) }
        } finally { NativeBridge.closeDb(database) }
        state.progress = "数据库检查完成（$records 条），正在检查资源文件"
        store.save(state)
        val world = File(state.world)
        for (file in world.walkTopDown()) {
            currentCoroutineContext().ensureActive()
            if (file.isDirectory || file.extension.lowercase() !in setOf("lang", "mcfunction", "json")) continue
            val relative = file.relativeTo(world).invariantSeparatorsPath
            if (relative.startsWith("db/")) continue
            val checkpoint = state.entries.size
            try {
                require(file.length() <= 8 * 1024 * 1024) { "文本文件超过 8 MiB" }
                val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.readBytes())).toString()
                fun add(start: Int, end: Int, text: String, json: Boolean, label: String) {
                    if (text.isBlank()) return
                    addEntry(TextEntry(sha("$relative/$start/$end".toByteArray()), "$relative/$label", text,
                        file = relative, start = start, end = end, mode = if (json) "file-json" else "file-text"))
                }
                if (file.extension.lowercase() in setOf("lang", "mcfunction")) {
                    var offset = 0
                    var lineNo = 0
                    for (line in Regex("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").findAll(source)) {
                        if (line.value.isEmpty()) continue
                        lineNo++
                        val body = line.value.trimEnd('\r', '\n')
                        if (body.isNotBlank() && !body.trimStart().startsWith('#')) {
                            if (file.extension.lowercase() == "lang") {
                                val separator = body.indexOf('=')
                                if (separator > 0) add(offset + separator + 1, offset + body.length, body.substring(separator + 1), false, "第 $lineNo 行")
                            } else {
                                val plan = DisplayCommand.inspect(body)
                                state.issues += plan.issues.map { "$relative:$lineNo：$it，保留未知参数" }
                                plan.spans.forEach { add(offset + it.start, offset + it.end, it.text, it.json, "第 $lineNo 行") }
                            }
                        }
                        offset += line.value.length
                    }
                } else {
                    val strings = DisplayCommand.jsonStrings(source)
                    var known = false
                    for (text in strings) {
                        val path = text.path
                        val dialogue = path.firstOrNull() == "minecraft:npc_dialogue" && path.getOrNull(1) == "scenes"
                        val plainDialogue = dialogue && (path.size == 4 && path.last() in setOf("text", "npc_name") || path.size == 6 && path[3] == "buttons" && path.last() == "name")
                        val rawText = path.lastOrNull() == "text" && path.size >= 3 && path[path.size - 3] == "rawtext"
                        val withString = path.size >= 2 && path[path.size - 2] == "with" &&
                            strings.any { it.path == path.dropLast(2) + "translate" }
                        if (plainDialogue || rawText || withString) {
                            add(text.start, text.end, text.text, true, path.joinToString("/")); known = true
                        }
                        if (dialogue && path.getOrNull(path.size - 2) in setOf("commands", "on_open_commands", "on_close_commands")) {
                            val plan = DisplayCommand.inspect(text.text)
                            state.issues += plan.issues.map { "$relative/${path.joinToString("/")}：$it，未修改" }
                            plan.spans.forEachIndexed { index, span ->
                                addEntry(TextEntry(sha("$relative/${text.start}/command/$index".toByteArray()),
                                    "$relative/${path.joinToString("/")}/显示文本[$index]", span.text,
                                    file = relative, start = text.start, end = text.end, mode = "file-command", span = index))
                            }
                        }
                    }
                    if (!known && strings.isNotEmpty()) state.issues += "$relative：没有已识别的显示文本 schema，未修改资源或脚本标识符"
                }
            } catch (failure: Exception) {
                state.entries.subList(checkpoint, state.entries.size).clear()
                state.issues += "$relative：${failure.message}，未修改"
            }
        }
        require(state.entries.size <= 100_000) { "可审阅文本超过 100,000 项" }
        state.stage = "提取完成"; state.progress = "${state.entries.size} 项文字，${state.issues.size} 条保留记录"
        store.save(state)
    }

    fun validate(entry: TextEntry) {
        if (entry.status == "保留原文" || entry.status == "待处理" || entry.status == "失败") return
        require(entry.translation.isNotBlank()) { "译文不能为空" }
        require(ProtectedText.protect(entry.original).mapping.map { it.second } == ProtectedText.protect(entry.translation).mapping.map { it.second }) { "占位符、格式或变量发生变化" }
        if (entry.mode in setOf("file-text", "command", "file-command")) require(entry.translation.none { it == '\n' || it == '\r' }) { "不允许插入新命令行" }
    }

    private suspend fun copyWorld(source: File, target: File) {
        require(target.mkdir())
        var total = 0L; var count = 0
        for (file in source.walkTopDown()) {
            currentCoroutineContext().ensureActive()
            require(!Files.isSymbolicLink(file.toPath()))
            val relative = file.relativeTo(source).invariantSeparatorsPath
            if (relative.isEmpty()) continue
            require(++count <= 20_000 && relative.split('/').size <= 32)
            val destination = File(target, relative)
            if (file.isDirectory) require(destination.mkdir())
            else file.inputStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(buffer); if (size < 0) break
                        total += size; require(total <= 2L * 1024 * 1024 * 1024)
                        output.write(buffer, 0, size)
                    }
                }
            }
        }
    }

    suspend fun export(state: ProjectState): File {
        state.entries.forEach { validate(it) }
        val target = File(store.directory, "export-work-${System.nanoTime()}")
        val exportedFolder = File(context.cacheDir, "world-exports").apply { mkdirs() }
        val output = File(exportedFolder, "MinecraftWorldTranslator-${state.id}-${System.nanoTime()}.mcworld")
        val restored = File(store.directory, "export-check-${System.nanoTime()}")
        try {
            copyWorld(File(state.world), target)
            val changes = state.entries.filter { it.status in setOf("自动翻译", "手动修改") && it.translation != it.original }
            val expected = linkedMapOf<String, ByteArray>()
            val db = NativeBridge.openDb(File(target, "db").absolutePath)
            try {
                for ((key, items) in changes.filter { it.recordKey.isNotEmpty() }.groupBy { it.recordKey }) {
                    currentCoroutineContext().ensureActive()
                    val original = File(store.directory, "records/$key.nbt").readBytes()
                    val record = LittleEndianNbt.parse(original)
                    val replacements = linkedMapOf<List<String>, String>()
                    for ((path, group) in items.groupBy { it.path }) {
                        val text = record.texts.single { it.path == path }.value
                        if (group.first().mode in setOf("command", "rawtext")) {
                            val rawtext = group.first().mode == "rawtext"
                            val plan = DisplayCommand.inspect(if (rawtext) "tellraw @a $text" else text)
                            group.forEach { require(plan.spans[it.span].text == it.original) }
                            val result = plan.rewrite(group.associate { it.span to it.translation })
                            replacements[path] = if (rawtext) result.removePrefix("tellraw @a ") else result
                        } else {
                            require(group.size == 1 && text == group[0].original)
                            replacements[path] = group[0].translation
                        }
                    }
                    val rewritten = record.rewrite(replacements)
                    NativeBridge.write(db, arrayOf(unhex(key)), arrayOf(original), arrayOf(rewritten))
                    expected[key] = rewritten
                }
            } finally { NativeBridge.closeDb(db) }
            // Verify every record against the unchanged working world, not only changed keys.
            val baseline = linkedMapOf<String, String>()
            forRecords(File(state.world)) { key, value -> if (value != null) baseline[hex(key)] = sha(value) else error("无法完整验证超大数据库记录，拒绝导出") }
            expected.forEach { (key, value) -> baseline[key] = sha(value) }
            val reread = linkedMapOf<String, String>()
            forRecords(target) { key, value -> reread[hex(key)] = sha(requireNotNull(value)) }
            require(baseline == reread) { "写回数据库后完整性校验失败" }
            for ((relative, items) in changes.filter { it.file.isNotEmpty() }.groupBy { it.file }) {
                currentCoroutineContext().ensureActive()
                val file = File(target, relative)
                val content = file.readText()
                val result = StringBuilder(content)
                var boundary = content.length
                for (group in items.groupBy { it.start to it.end }.values.sortedByDescending { it[0].start }) {
                    val entry = group[0]
                    require(entry.end <= boundary && entry.start <= entry.end)
                    val original = content.substring(entry.start, entry.end)
                    val replacement = if (entry.mode == "file-command") {
                        val command = Json.parseToJsonElement(original).jsonPrimitive.content
                        val plan = DisplayCommand.inspect(command)
                        group.forEach { require(plan.spans[it.span].text == it.original) }
                        JsonPrimitive(plan.rewrite(group.associate { it.span to it.translation })).toString()
                    } else {
                        require(group.size == 1)
                        require(if (entry.mode == "file-json") Json.parseToJsonElement(original).jsonPrimitive.content == entry.original else original == entry.original)
                        if (entry.mode == "file-json") JsonPrimitive(entry.translation).toString() else entry.translation
                    }
                    result.replace(entry.start, entry.end, replacement)
                    boundary = entry.start
                }
                if (file.extension.lowercase() == "json") DisplayCommand.jsonStrings(result.toString())
                file.writeText(result.toString())
            }
            val coroutine = currentCoroutineContext()
            SafeWorldArchive().export(target, output) { coroutine.ensureActive() }
            val root = SafeWorldArchive().extract(output, restored) { coroutine.ensureActive() }
            val exportedRecords = linkedMapOf<String, String>()
            forRecords(root) { key, value -> exportedRecords[hex(key)] = sha(requireNotNull(value)) }
            require(exportedRecords == baseline) { "导出后数据库重新读取失败" }
            val expectedFiles = target.walkTopDown().filter { it.isFile && !it.relativeTo(target).invariantSeparatorsPath.startsWith("db/") }.associate { it.relativeTo(target).invariantSeparatorsPath to shaFile(it) }
            val actualFiles = root.walkTopDown().filter { it.isFile && !it.relativeTo(root).invariantSeparatorsPath.startsWith("db/") }.associate { it.relativeTo(root).invariantSeparatorsPath to shaFile(it) }
            require(expectedFiles == actualFiles) { "导出文件校验失败" }
            state.export = output.absolutePath
            state.stage = "导出验证通过"
            state.progress = "数据库重新读取通过；待处理和失败项目保留原文"
            File(store.directory, "report.json").writeText(state.json().toString(2))
            return output
        } catch (failure: Throwable) { output.delete(); throw failure }
        finally { target.deleteRecursively(); restored.deleteRecursively() }
    }
}
