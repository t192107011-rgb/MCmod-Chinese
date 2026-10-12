package cn.mcworld.translator

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import cn.mcworld.translator.core.DisplayCommand
import cn.mcworld.translator.core.LittleEndianNbt
import cn.mcworld.translator.core.ProtectedText
import cn.mcworld.translator.core.SafeWorldArchive
import cn.mcworld.translator.core.BigEndianNbt
import cn.mcworld.translator.core.JavaRegion
import cn.mcworld.translator.engine.NativeBridge
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

class WorldOperations(private val context: Context, private val store: ProjectStore) {
    private fun javaComponentSpans(source: String): List<DisplayCommand.JsonString> =
        DisplayCommand.jsonStrings(source).filter { it.path.lastOrNull() == "text" }

    private fun rewriteJavaComponent(source: String, changes: Map<Int, String>): String {
        val spans = javaComponentSpans(source)
        require(changes.keys.all { it in spans.indices })
        val result = StringBuilder(source)
        for ((index, span) in spans.withIndex().toList().asReversed()) {
            val translated = changes[index] ?: continue
            require(ProtectedText.protect(translated).mapping.map { it.second } ==
                ProtectedText.protect(span.text).mapping.map { it.second }) { "Java 文本格式代码发生变化" }
            result.replace(span.start, span.end, JsonPrimitive(translated).toString())
        }
        val updated = result.toString()
        val before = DisplayCommand.jsonStrings(source)
        val after = DisplayCommand.jsonStrings(updated)
        require(before.map { it.path } == after.map { it.path }) { "Java 文本结构发生变化" }
        val editable = spans.map { it.path }.toSet()
        require(before.zip(after).all { (old, new) -> old.path in editable || old.text == new.text }) { "Java 文本非显示字段发生变化" }
        return updated
    }
    /** NPC Actions is a JSON string inside NBT. Only button labels are editable. */
    private fun npcButtons(source: String): List<DisplayCommand.JsonString> =
        DisplayCommand.jsonStrings(source).filter { span ->
            span.path.size == 2 && span.path[0].toIntOrNull() != null && span.path[1] == "button_name"
        }

    private fun rewriteNpcButtons(source: String, changes: Map<Int, String>): String {
        val buttons = npcButtons(source)
        require(changes.keys.all { it in buttons.indices }) { "NPC button index changed" }
        val result = StringBuilder(source)
        for ((index, button) in buttons.withIndex().toList().asReversed()) {
            val translated = changes[index] ?: continue
            require(ProtectedText.protect(translated).mapping.map { it.second } ==
                ProtectedText.protect(button.text).mapping.map { it.second }) { "NPC button protected text changed" }
            result.replace(button.start, button.end, JsonPrimitive(translated).toString())
        }
        val rewritten = result.toString()
        val oldStrings = DisplayCommand.jsonStrings(source)
        val newStrings = DisplayCommand.jsonStrings(rewritten)
        require(oldStrings.map { it.path } == newStrings.map { it.path }) { "NPC action structure changed" }
        val editable = buttons.map { it.path }.toSet()
        require(oldStrings.zip(newStrings).all { (old, new) -> old.path in editable || old.text == new.text }) {
            "NPC action command changed"
        }
        return rewritten
    }
    suspend fun table(state: ProjectState, uri: Uri, importing: Boolean) {
        val resolver = context.contentResolver
        if (importing) {
            batchImport(state, listOf(uri))
        } else {
            val document = DocumentFile.fromSingleUri(context, uri) ?: error("翻译表文档不可用")
            val metadata = JSONObject()
                .put("id", state.id).put("world", state.world).put("language", state.language)
                .put("stage", state.stage).put("progress", state.progress).put("busy", state.busy)
                .put("export", state.export).put("issues", org.json.JSONArray(state.issues))
                .put("conflictChoices", JSONObject(state.conflictChoices as Map<*, *>))
                .put("importConflicts", org.json.JSONArray(state.importConflicts.map { it.json() }))
                .put("sourceName", state.sourceName).put("progressCurrent", state.progressCurrent)
                .put("progressTotal", state.progressTotal)
                .put("extractedRecords", JSONObject(state.extractedRecords.mapKeys { it.key.toString() }))
                .put("activeOperation", state.activeOperation)
                .put("resourceDone", org.json.JSONArray(state.resourceDone.toList()))
                .put("sourceLanguages", org.json.JSONArray(state.sourceLanguages.toList()))
                .put("edition", state.edition)
            try {
                (resolver.openOutputStream(uri, "w") ?: error("翻译表不可写")).bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(metadata.toString().dropLast(1))
                    writer.write(",\"entries\":[")
                    for ((index, entry) in state.entries.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        if (index > 0) writer.write(",")
                        writer.write(entry.json().toString())
                    }
                    writer.write("]}")
                }
            } catch (failure: Throwable) { runCatching { document.delete() }; throw failure }
            state.stage = "翻译表已导出"; state.progress = "单个 JSON，包含 ${state.entries.size} 项原文、译文、来源与处理状态"
        }
    }
    suspend fun checkTable(state: ProjectState) {
        val errors = mutableListOf<String>()
        val ids = mutableSetOf<String>()
        state.entries.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            if (!ids.add(entry.id)) errors += "第 ${index + 1} 项：重复 ID ${entry.id}"
            if (entry.status in setOf("自动翻译", "手动修改")) {
                runCatching { validate(entry) }.onFailure { errors += "${entry.id}：${it.message}" }
                if (entry.translation == entry.original) errors += "${entry.id}：译文与原文一致，需要人工确认"
            } else if (entry.status == "待处理" && entry.translation.isNotBlank()) errors += "${entry.id}：待处理状态却有译文"
        }
        File(store.directory, "translation-check-report.json").writeText(
            JSONObject().put("totalEntries", state.entries.size).put("errors", org.json.JSONArray(errors)).toString(2))
        state.stage = "翻译表检查完成"
        state.progress = "检查 ${state.entries.size} 项，发现 ${errors.size} 个问题" + errors.take(2).joinToString("；", prefix = if (errors.isEmpty()) "" else "：")
    }

    suspend fun batchExport(state: ProjectState, uri: Uri, size: Int) {
        val document = DocumentFile.fromSingleUri(context, uri) ?: error("目标文档不可用")
        try {
            (context.contentResolver.openOutputStream(uri, "w") ?: error("无法写入 ZIP")).use { output ->
                TranslationBatches.export(state, size, output) { state.progress = it }
            }
        } catch (failure: Throwable) { runCatching { document.delete() }; throw failure }
        state.stage = "分批翻译表已导出"
    }

    suspend fun batchImport(state: ProjectState, uris: List<Uri>, resolution: String = "preview") {
        val inputs = uris.map { uri ->
            val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment ?: "文件"
            name to (context.contentResolver.openInputStream(uri) ?: error("无法读取 $name"))
        }
        val (documents, errors) = TranslationBatches.collect(inputs)
        val preview = TranslationBatches.preview(state, documents, errors, ::validate)
        val report = JSONObject().put("new", preview.changes.size).put("conflicts", preview.conflicts.size)
            .put("skipped", preview.skipped).put("errors", org.json.JSONArray(preview.errors))
        File(store.directory, "table-import-report.json").writeText(report.toString(2))
        state.issues.removeAll { it.startsWith("批量导入：") }
        state.issues.addAll(preview.errors.take(100).map { "批量导入：$it" })
        if (preview.errors.size > 100) state.issues += "批量导入：另有 ${preview.errors.size - 100} 项异常"
        if (resolution == "preview") {
            state.conflictChoices.clear()
            state.importConflicts.clear()
            val indexed = state.entries.associateBy { it.id }
            state.importConflicts.addAll(preview.conflicts.map { proposal ->
                val old = indexed.getValue(proposal.id)
                ImportConflict(proposal.id, old.original, old.translation, proposal.translation)
            })
            state.stage = "批量导入预览"
            state.progress = "新增 ${preview.changes.size}，冲突 ${preview.conflicts.size}，异常 ${preview.errors.size}；选择处理方式后提交"
            return
        }
        require(resolution in setOf("keep", "replace", "valid-only", "custom"))
        require(resolution in setOf("valid-only", "custom") || preview.errors.isEmpty()) { "存在损坏记录；请先查看报告或选择仅导入有效记录" }
        val indexed = state.entries.associateBy { it.id }
        val selectedConflicts = when (resolution) {
            "replace" -> preview.conflicts
            "custom" -> preview.conflicts.filter { state.conflictChoices[it.id] == true }
            else -> emptyList()
        }
        (preview.changes + selectedConflicts).forEach { proposal ->
            indexed.getValue(proposal.id).apply { translation = proposal.translation; status = "手动修改"; reason = "从批量翻译表导入" }
        }
        state.importConflicts.clear(); state.conflictChoices.clear()
        state.stage = "批量导入完成"
        state.progress = "导入 ${preview.changes.size + selectedConflicts.size} 项，跳过 ${preview.skipped} 项，冲突 ${preview.conflicts.size} 项，异常 ${preview.errors.size} 项"
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

    private suspend fun extractJavaRegions(state: ProjectState, world: File) {
        val regions = world.walkTopDown().filter { it.isFile && it.extension.equals("mca", true) &&
            (it.parentFile?.name == "region" || it.parentFile?.name == "entities") }
            .sortedBy { it.relativeTo(world).invariantSeparatorsPath }.toList()
        val indexed = regions.mapNotNull { file ->
            runCatching { file to JavaRegion.indices(file) }.onFailure {
                state.issues += "${file.relativeTo(world).invariantSeparatorsPath}：${it.message}，未修改"
            }.getOrNull()
        }
        val total = indexed.sumOf { it.second.size }.toLong()
        var scanned = indexed.filter { it.first.relativeTo(world).invariantSeparatorsPath in state.javaDone }
            .sumOf { it.second.size }.toLong()
        state.progressCurrent = scanned
        state.progressTotal = total
        store.writeProgress("Java 区域区块 $scanned / $total；已提取 ${state.entries.size} 项", scanned, total)
        store.save(state)
        var checkpoint = 0
        for ((file, indices) in indexed) {
            currentCoroutineContext().ensureActive()
            val relative = file.relativeTo(world).invariantSeparatorsPath
            if (relative in state.javaDone) continue
            for (index in indices) {
                currentCoroutineContext().ensureActive()
                val before = state.entries.size
                val key = "java:$relative#$index"
                try {
                    val bytes = requireNotNull(JavaRegion.read(file, index))
                    val record = BigEndianNbt.parse(bytes, maxNodes = 100_000, copyBytes = false)
                    val fields = record.texts.associateBy { it.path }
                    for (text in record.texts) {
                        val path = text.path
                        val owner = (1 until path.size).asSequence().mapNotNull { depth ->
                            fields[path.take(depth) + "id"]?.value?.substringAfter(':')?.lowercase()
                        }.lastOrNull() ?: continue
                        val sign = owner == "sign" || owner.endsWith("_sign")
                        val commandBlock = owner in setOf("command_block", "chain_command_block", "repeating_command_block")
                        val signField = path.last() in setOf("Text1", "Text2", "Text3", "Text4") ||
                            path.size >= 3 && path[path.size - 2] == "messages" &&
                            path[path.size - 3] in setOf("front_text", "back_text")
                        if (sign && signField && text.value.isNotBlank()) {
                            if (text.value.trimStart().startsWith('{')) {
                                javaComponentSpans(text.value).forEachIndexed { span, component ->
                                    if (component.text.isNotBlank()) state.entries += TextEntry(
                                        sha("$key/${path}/$span".toByteArray()), "$key/${path.joinToString("/")}/文字[$span]",
                                        component.text, recordKey = key, path = path, mode = "java-component", span = span)
                                }
                            } else state.entries += TextEntry(sha("$key/$path".toByteArray()),
                                "$key/${path.joinToString("/")}", text.value, recordKey = key, path = path, mode = "java-text")
                        }
                        if (commandBlock && path.last() == "Command" && text.value.isNotBlank()) {
                            val plan = DisplayCommand.inspect(text.value)
                            state.issues += plan.issues.map { "$key/${path.joinToString("/")}：$it，命令保留原文" }
                            plan.spans.forEachIndexed { span, segment -> state.entries += TextEntry(
                                sha("$key/${path}/command/$span".toByteArray()),
                                "$key/${path.joinToString("/")}/显示文本[$span]", segment.text,
                                recordKey = key, path = path, mode = "command", span = span) }
                        }
                    }
                    require(state.entries.size <= 100_000) { "可审阅文本超过 100,000 项" }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    state.entries.subList(before, state.entries.size).clear()
                    state.issues += "$key：${failure.message}，未修改"
                }
                scanned++
                if (scanned % 32L == 0L) store.writeProgress(
                    "Java 区域区块 $scanned / $total；已提取 ${state.entries.size} 项", scanned, total)
            }
            state.javaDone += relative
            state.progressCurrent = scanned
            state.progress = "Java 区域区块 $scanned / $total；已提取 ${state.entries.size} 项"
            if (++checkpoint % 8 == 0) store.save(state)
            store.writeProgress(state.progress, scanned, total)
        }
        store.save(state)
    }

    suspend fun extract(state: ProjectState) {
        require(state.world.isNotEmpty())
        if (state.edition.isBlank()) state.edition = SafeWorldArchive().edition(File(state.world)).name.lowercase()
        // Completed key ranges are durable checkpoints; unfinished ranges are rescanned.
        if (state.extractedRecords.isEmpty() && state.javaDone.isEmpty() && state.resourceDone.isEmpty()) {
            state.entries.clear()
            state.issues.clear()
        } else {
            state.entries.removeAll { it.file.isNotEmpty() && it.file !in state.resourceDone }
            state.issues.removeAll { issue -> !issue.startsWith("db/") && !issue.startsWith("java:") && state.resourceDone.none { file ->
                issue.startsWith("$file:") || issue.startsWith("$file/") || issue.startsWith("$file：")
            } }
        }
        if (state.edition == "java") {
            extractJavaRegions(state, File(state.world))
            state.progress = "Java 区块检查完成，正在检查资源文件"
            store.writeProgress(state.progress, state.progressCurrent, state.progressTotal)
        } else {
        val originals = File(store.directory, "records").apply { mkdirs() }
        fun addEntry(entry: TextEntry) {
            require(state.entries.size < 100_000) { "可审阅文本超过 100,000 项" }
            state.entries.add(entry)
        }
        val ranges = listOf(0, 64, 128, 192)
        val totalDb = NativeBridge.openDb(File(state.world, "db").absolutePath)
        try {
            currentCoroutineContext().ensureActive()
        val total = NativeBridge.countRecords(totalDb).also { require(it >= 0) }
        val scanned = java.util.concurrent.atomic.AtomicLong(state.extractedRecords.values.sum())
        state.progressTotal = total
        state.progressCurrent = scanned.get()
        store.writeProgress("已检查 ${scanned.get()} / $total 条数据库记录", scanned.get(), total)
        store.save(state)

        suspend fun scanRange(start: Int): Pair<ProjectState, Long> {
            val part = ProjectState(state.id)
            fun addEntry(entry: TextEntry) {
                require(part.entries.size < 100_000) { "可审阅文本超过 100,000 项" }
                part.entries.add(entry)
            }
            var records = 0L
            var lastReported = 0L
                val cursor = NativeBridge.cursorRange(totalDb, start, minOf(start + 64, 256))
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val item = NativeBridge.nextTextCandidate(cursor) ?: break
                        val count = requireNotNull(item[2])
                        val increment = (count[0].toInt() and 255) or ((count[1].toInt() and 255) shl 8)
                        records += increment
                        val processed = scanned.addAndGet(increment.toLong())
                        val key = item[0]
                        val raw = item[1]
                        if (key != null) {
            val actor = key.take(11).toByteArray().contentEquals("actorprefix".toByteArray())
            val player = key.contentEquals("~local_player".toByteArray()) || key.toString(Charsets.ISO_8859_1).startsWith("player_server_")
            val chunk = key.size in listOf(9, 13) && (key.last().toInt() and 255) in listOf(0x31, 0x32)
            if (actor || player || chunk) {
                val keyHex = hex(key)
                val checkpoint = part.entries.size
                if (raw == null) part.issues += "db/$keyHex：记录超过 32 MiB，未修改"
                else try {
                    val record = LittleEndianNbt.parse(raw, maxNodes = 100_000, copyBytes = false)
                    val fields = record.texts.associateBy { it.path }
                    val before = part.entries.size
                    fun add(text: LittleEndianNbt.Text, mode: String = "text") {
                        if (text.value.isBlank()) return
                        val actualMode = if (mode == "text" && text.value.trimStart().startsWith('{')) "rawtext" else mode
                        if (actualMode in setOf("command", "rawtext")) {
                            val plan = DisplayCommand.inspect(if (actualMode == "rawtext") "tellraw @a " + text.value else text.value)
                            part.issues += plan.issues.map { "db/$keyHex/${text.path.joinToString("/")}：$it，未修改未知参数" }
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
                        val id = (fields[listOf(root, "id")] ?: fields[listOf(root, "identifier")])?.value?.substringAfter(':')
                        if (id in listOf("Sign", "HangingSign")) {
                            if (path == listOf(root, "Text") || path == listOf(root, "FrontText", "Text") || path == listOf(root, "BackText", "Text")) add(text)
                        }
                        if (id == "CommandBlock" && path == listOf(root, "Command")) add(text, "command")
                        if (id?.lowercase() == "npc" && path.size == 2) {
                            when (path.last()) {
                                "InterativeText", "Dialog", "Dialogue" -> add(text, "npc-dialogue")
                                "Actions" -> try {
                                    npcButtons(text.value).forEachIndexed { index, button ->
                                        if (button.text.isNotBlank()) addEntry(TextEntry(
                                            sha("$keyHex/${text.path}/npc-button/$index".toByteArray()),
                                            "db/$keyHex/${path.joinToString("/")}/按钮[$index]", button.text,
                                            recordKey = keyHex, path = path, mode = "npc-actions", span = index))
                                    }
                                } catch (failure: Exception) {
                                    part.issues += "db/$keyHex/${path.joinToString("/")}：NPC 按钮格式无法确认（${failure.message}），保留原文"
                                }
                            }
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
                    if (part.entries.size > before) File(originals, "$keyHex.nbt").writeBytes(raw)
                } catch (failure: Exception) {
                    part.entries.subList(checkpoint, part.entries.size).clear()
                    part.issues += "db/$keyHex：${failure.message}，未修改"
                }
            }
                        }
                        if (records - lastReported >= 1000) {
                            synchronized(store) {
                                store.writeProgress("已检查 $processed / $total 条数据库记录；提取 ${state.entries.size + part.entries.size} 项文字",
                                    processed, total)
                            }
                            lastReported = records
                        }
                    }
                } finally { NativeBridge.closeCursor(cursor) }
            return part to records
        }
        kotlinx.coroutines.coroutineScope {
            val dispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2)
            val pending = ranges.filterNot { state.extractedRecords.containsKey(it) }
                .map { start -> start to async(dispatcher) { scanRange(start) } }
            for ((start, job) in pending) {
                val (part, count) = job.await()
                state.entries.addAll(part.entries)
                state.issues.addAll(part.issues)
                require(state.entries.size <= 100_000) { "可审阅文本超过 100,000 项" }
                state.extractedRecords[start] = count
                state.progressCurrent = state.extractedRecords.values.sum()
                state.progress = "已完成 ${state.extractedRecords.size} / 4 个数据库区间；已检查 ${state.progressCurrent} / $total 条"
                store.save(state)
            }
        }
        state.progressCurrent = total
        state.progress = "数据库检查完成（$total 条），正在检查资源文件"
        store.writeProgress(state.progress, total, total)
        store.save(state)
        } finally { NativeBridge.closeDb(totalDb) }
        }
        val world = File(state.world)
        val languageLocales = mapOf("en" to "en_US", "ru" to "ru_RU", "de" to "de_DE",
            "fr" to "fr_FR", "es" to "es_ES")
        val selectedLanguages = state.sourceLanguages.ifEmpty { setOf(state.language) }
        val locales = selectedLanguages.mapNotNull(languageLocales::get).toSet() + setOf("en_US", "zh_CN") +
            if ("en" in selectedLanguages) setOf("en_GB") else emptySet()
        fun wanted(file: File): Boolean {
            if (!file.isFile || file.extension.lowercase() !in setOf("lang", "mcfunction", "json")) return false
            val relative = file.relativeTo(world).invariantSeparatorsPath
            if (relative.startsWith("db/")) return false
            val localized = file.extension.equals("lang", ignoreCase = true) && relative.contains("/texts/") &&
                file.nameWithoutExtension.matches(Regex("[a-z]{2,3}_[A-Z]{2}"))
            val javaLang = state.edition == "java" && (relative.startsWith("assets/") || relative.contains("/assets/")) &&
                relative.contains("/lang/") && file.extension.equals("json", true) &&
                file.nameWithoutExtension.matches(Regex("[a-z]{2,3}_[a-z]{2}"))
            return (!localized || file.nameWithoutExtension in locales) &&
                (!javaLang || file.nameWithoutExtension in locales.map(String::lowercase))
        }
        val files = world.walkTopDown()
            .filter(::wanted)
            .sortedBy { it.relativeTo(world).invariantSeparatorsPath }.toList()
        val selectedPaths = files.mapTo(hashSetOf()) { it.relativeTo(world).invariantSeparatorsPath }
        val excluded = state.resourceDone.filterNot { it in selectedPaths }
        state.resourceDone.retainAll(selectedPaths)
        state.entries.removeAll { it.file.isNotEmpty() && it.file !in selectedPaths }
        state.issues.removeAll { issue -> excluded.any { file ->
            issue.startsWith("$file:") || issue.startsWith("$file/") || issue.startsWith("$file：")
        } }
        val totalFiles = files.size.toLong()
        state.progressCurrent = state.resourceDone.size.toLong()
        state.progressTotal = totalFiles
        state.progress = "资源文件 ${state.progressCurrent} / $totalFiles"
        store.writeProgress(state.progress, state.progressCurrent, totalFiles)
        store.save(state)

        suspend fun scanResource(file: File): Pair<List<TextEntry>, List<String>> {
            currentCoroutineContext().ensureActive()
            val relative = file.relativeTo(world).invariantSeparatorsPath
            val part = ProjectState(state.id)
            fun addEntry(entry: TextEntry) {
                require(part.entries.size < 100_000) { "可审阅文本超过 100,000 项" }
                part.entries.add(entry)
            }
            val checkpoint = part.entries.size
            try {
                require(file.length() <= 8 * 1024 * 1024) { "文本文件超过 8 MiB" }
                val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.readBytes())).toString()
                fun add(start: Int, end: Int, text: String, json: Boolean, label: String) {
                    if (text.isBlank()) return
                    addEntry(TextEntry(sha("$relative/$start/$end".toByteArray()), "$relative/$label", text,
                        status = if (relative.endsWith("/zh_CN.lang", ignoreCase = true) ||
                            state.edition == "java" && relative.endsWith("/zh_cn.json", ignoreCase = true)) "保留原文" else "待处理",
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
                                part.issues += plan.issues.map { "$relative:$lineNo：$it，保留未知参数" }
                                plan.spans.forEach { add(offset + it.start, offset + it.end, it.text, it.json, "第 $lineNo 行") }
                            }
                        }
                        offset += line.value.length
                    }
                } else {
                    val strings = DisplayCommand.jsonStrings(source)
                    val javaLang = state.edition == "java" && (relative.startsWith("assets/") || relative.contains("/assets/")) &&
                        relative.contains("/lang/") && file.nameWithoutExtension.matches(Regex("[a-z]{2,3}_[a-z]{2}"))
                    val translatePaths = strings.asSequence().filter { it.path.lastOrNull() == "translate" }.map { it.path }.toHashSet()
                    var known = false
                    for (text in strings) {
                        val path = text.path
                        if (javaLang && path.size == 1) {
                            add(text.start, text.end, text.text, true, "语言键 ${path.single()}")
                            known = true
                            continue
                        }
                        val dialogue = path.firstOrNull() == "minecraft:npc_dialogue" && path.getOrNull(1) == "scenes"
                        val plainDialogue = dialogue && (path.size == 4 && path.last() in setOf("text", "npc_name") || path.size == 6 && path[3] == "buttons" && path.last() == "name")
                        val rawText = path.lastOrNull() == "text" && path.size >= 3 && path[path.size - 3] == "rawtext"
                        val withString = path.size >= 2 && path[path.size - 2] == "with" &&
                            (path.dropLast(2) + "translate") in translatePaths
                        if (plainDialogue || rawText || withString) {
                            add(text.start, text.end, text.text, true, path.joinToString("/")); known = true
                        }
                        if (dialogue && path.getOrNull(path.size - 2) in setOf("commands", "on_open_commands", "on_close_commands")) {
                            val plan = DisplayCommand.inspect(text.text)
                            part.issues += plan.issues.map { "$relative/${path.joinToString("/")}：$it，未修改" }
                            plan.spans.forEachIndexed { index, span ->
                                addEntry(TextEntry(sha("$relative/${text.start}/command/$index".toByteArray()),
                                    "$relative/${path.joinToString("/")}/显示文本[$index]", span.text,
                                    file = relative, start = text.start, end = text.end, mode = "file-command", span = index))
                            }
                        }
                    }
                    if (!known && strings.isNotEmpty()) part.issues += "$relative：没有已识别的显示文本 schema，未修改资源或脚本标识符"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                part.entries.subList(checkpoint, part.entries.size).clear()
                part.issues += "$relative：${failure.message}，未修改"
            }
            return part.entries to part.issues
        }
        kotlinx.coroutines.coroutineScope {
            val dispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2)
            val pending = files.filterNot { it.relativeTo(world).invariantSeparatorsPath in state.resourceDone }
            for (batch in pending.chunked(8)) {
                currentCoroutineContext().ensureActive()
                val results = batch.map { file -> async(dispatcher) { scanResource(file) } }.map { it.await() }
                for ((index, result) in results.withIndex()) {
                    state.entries.addAll(result.first)
                    state.issues.addAll(result.second)
                    state.resourceDone += batch[index].relativeTo(world).invariantSeparatorsPath
                }
                require(state.entries.size <= 100_000) { "可审阅文本超过 100,000 项" }
                state.progressCurrent = state.resourceDone.size.toLong()
                state.progress = "资源文件 ${state.progressCurrent} / $totalFiles；提取 ${state.entries.size} 项文字"
                store.save(state)
                store.writeProgress(state.progress, state.progressCurrent, totalFiles)
            }
        }
        require(state.entries.size <= 100_000) { "可审阅文本超过 100,000 项" }
        state.stage = "提取完成"; state.progress = "${state.entries.size} 项文字，${state.issues.size} 条保留记录"
        store.clearProgress()
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

    private suspend fun databaseDigest(world: File, overrides: Map<String, String> = emptyMap()): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val found = HashSet<String>()
        forRecords(world) { key, value ->
            val id = hex(key)
            val hash = overrides[id]?.also { found += id } ?: sha(requireNotNull(value))
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(key.size).array())
            digest.update(key)
            digest.update(unhex(hash))
        }
        require(found == overrides.keys) { "数据库记录缺失" }
        return hex(digest.digest())
    }

    private suspend fun filesDigest(world: File, skipDatabase: Boolean): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val files = world.walkTopDown().filter { it.isFile &&
            (!skipDatabase || !it.relativeTo(world).invariantSeparatorsPath.startsWith("db/")) }
            .map { it.relativeTo(world).invariantSeparatorsPath }.sorted().toList()
        for (relative in files) {
            currentCoroutineContext().ensureActive()
            val path = relative.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(path.size).array())
            digest.update(path)
            digest.update(unhex(shaFile(File(world, relative))))
        }
        return hex(digest.digest())
    }

    suspend fun export(state: ProjectState): File {
        state.entries.forEach { validate(it) }
        val edition = SafeWorldArchive().edition(File(state.world))
        val target = File(store.directory, "export-work-${System.nanoTime()}")
        val exportedFolder = File(context.cacheDir, "world-exports").apply { mkdirs() }
        val output = File(exportedFolder, "MinecraftWorldTranslator-${state.id}-${System.nanoTime()}.${if (edition == SafeWorldArchive.Edition.JAVA) "zip" else "mcworld"}")
        val restored = File(store.directory, "export-check-${System.nanoTime()}")
        try {
            copyWorld(File(state.world), target)
            val changes = state.entries.filter { it.status in setOf("自动翻译", "手动修改") && it.translation != it.original }
            val expected = linkedMapOf<String, String>()
            if (edition == SafeWorldArchive.Edition.JAVA) {
                for ((key, items) in changes.filter { it.recordKey.startsWith("java:") }.groupBy { it.recordKey }) {
                    currentCoroutineContext().ensureActive()
                    val relative = key.removePrefix("java:").substringBeforeLast('#')
                    val index = key.substringAfterLast('#').toInt()
                    require(!relative.startsWith('/') && relative.split('/').none { it in setOf("", ".", "..") })
                    val originalFile = File(state.world, relative)
                    val destination = File(target, relative)
                    require(originalFile.canonicalPath.startsWith(File(state.world).canonicalPath + File.separator))
                    val original = requireNotNull(JavaRegion.read(originalFile, index))
                    val record = BigEndianNbt.parse(original)
                    val replacements = linkedMapOf<List<String>, String>()
                    for ((path, group) in items.groupBy { it.path }) {
                        val text = record.texts.single { it.path == path }.value
                        replacements[path] = when (group.first().mode) {
                            "java-component" -> {
                                val spans = javaComponentSpans(text)
                                group.forEach { require(spans[it.span].text == it.original) }
                                rewriteJavaComponent(text, group.associate { it.span to it.translation })
                            }
                            "command" -> {
                                val plan = DisplayCommand.inspect(text)
                                group.forEach { require(plan.spans[it.span].text == it.original) }
                                plan.rewrite(group.associate { it.span to it.translation })
                            }
                            else -> {
                                require(group.size == 1 && text == group.single().original)
                                group.single().translation
                            }
                        }
                    }
                    val rewritten = record.rewrite(replacements)
                    JavaRegion.rewrite(destination, index, original, rewritten)
                }
            } else {
            val db = NativeBridge.openDb(File(target, "db").absolutePath)
            try {
                for ((key, items) in changes.filter { it.recordKey.isNotEmpty() }.groupBy { it.recordKey }) {
                    currentCoroutineContext().ensureActive()
                    val original = File(store.directory, "records/$key.nbt").readBytes()
                    val record = LittleEndianNbt.parse(original)
                    val replacements = linkedMapOf<List<String>, String>()
                    for ((path, group) in items.groupBy { it.path }) {
                        val text = record.texts.single { it.path == path }.value
                        if (group.first().mode == "npc-actions") {
                            val buttons = npcButtons(text)
                            group.forEach { require(buttons[it.span].text == it.original) }
                            replacements[path] = rewriteNpcButtons(text, group.associate { it.span to it.translation })
                        } else if (group.first().mode in setOf("command", "rawtext")) {
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
                    expected[key] = sha(rewritten)
                }
            } finally { NativeBridge.closeDb(db) }
            require(databaseDigest(File(state.world), expected) == databaseDigest(target)) { "写回数据库后完整性校验失败" }
            }
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
            if (edition == SafeWorldArchive.Edition.BEDROCK)
                require(databaseDigest(File(state.world), expected) == databaseDigest(root)) { "导出后数据库重新读取失败" }
            require(filesDigest(target, edition == SafeWorldArchive.Edition.BEDROCK) ==
                filesDigest(root, edition == SafeWorldArchive.Edition.BEDROCK)) { "导出文件校验失败" }
            state.export = output.absolutePath
            state.stage = "导出验证通过"
            state.progress = "${if (edition == SafeWorldArchive.Edition.JAVA) "Java 区域文件" else "基岩版数据库"}与导出文件校验通过；待处理和失败项目保留原文"
            File(store.directory, "report.json").writeText(JSONObject()
                .put("edition", edition.name.lowercase()).put("totalEntries", state.entries.size)
                .put("translated", state.entries.count { it.status in setOf("自动翻译", "手动修改") })
                .put("issues", org.json.JSONArray(state.issues)).toString(2))
            return output
        } catch (failure: Throwable) { output.delete(); throw failure }
        finally { target.deleteRecursively(); restored.deleteRecursively() }
    }
}

