package cn.mcworld.translator.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.DeflaterOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import kotlin.test.*
import org.junit.Test

class CoreTest {
    @Test fun javaRegionSignRoundTripAndWorldEdition() = inTemp { root ->
        val world = File(root, "java-world").apply { mkdirs() }
        GZIPOutputStream(File(world, "level.dat").outputStream()).use { it.write(byteArrayOf(10, 0, 0, 0)) }
        val region = File(world, "region").apply { mkdirs() }
        val source = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeByte(10); out.writeUTF("")
                out.writeByte(9); out.writeUTF("block_entities")
                out.writeByte(10); out.writeInt(1)
                out.writeByte(8); out.writeUTF("id"); out.writeUTF("minecraft:oak_sign")
                out.writeByte(10); out.writeUTF("front_text")
                out.writeByte(9); out.writeUTF("messages")
                out.writeByte(8); out.writeInt(1); out.writeUTF("{\"text\":\"Hello\"}")
                out.writeByte(0); out.writeByte(0); out.writeByte(0)
            }
        }.toByteArray()
        val packed = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { zip -> zip.write(source) } }.toByteArray()
        val file = File(region, "r.0.0.mca")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(3 * 4096L)
            raf.writeInt((2 shl 8) or 1)
            raf.seek(2 * 4096L)
            raf.writeInt(packed.size + 1); raf.writeByte(2); raf.write(packed)
        }
        assertEquals(SafeWorldArchive.Edition.JAVA, SafeWorldArchive().edition(world))
        assertEquals(listOf(0), JavaRegion.indices(file))
        assertContentEquals(source, JavaRegion.read(file, 0))
        val record = BigEndianNbt.parse(source)
        val path = listOf("0", "block_entities", "0", "front_text", "messages", "0")
        val changed = record.rewrite(mapOf(path to "{\"text\":\"你好\"}"))
        JavaRegion.rewrite(file, 0, source, changed)
        assertEquals("{\"text\":\"你好\"}", BigEndianNbt.parse(JavaRegion.read(file, 0)!!).texts.single { it.path == path }.value)
        val archive = File(root, "world.zip")
        SafeWorldArchive().export(world, archive)
        val reread = SafeWorldArchive().extract(archive, File(root, "reread"))
        assertEquals(SafeWorldArchive.Edition.JAVA, SafeWorldArchive().edition(reread))
        assertContentEquals(changed, JavaRegion.read(File(reread, "region/r.0.0.mca"), 0))
    }
    @Test fun rawtextPreservesStructureAndEscaping() {
        val source = "tellraw @a { \"rawtext\": [ {\"text\":\"Hello %s §a\"}, {\"selector\":\"@p\"}, {\"score\":{\"name\":\"@s\",\"objective\":\"quest\"}}, {\"translate\":\"map.quest\",\"with\":[\"Find the key\", {\"rawtext\":[{\"text\":\"Welcome\"}]}]} ] }"
        val plan = DisplayCommand.inspect(source)
        assertEquals(listOf("Hello %s §a", "Find the key", "Welcome"), plan.spans.map { it.text })
        assertTrue(plan.issues.isEmpty())
        val rewritten = plan.rewrite(mapOf(0 to "你好 %s §a", 1 to "找到钥匙", 2 to "欢迎"))
        assertEquals(source.replace("Hello %s §a", "你好 %s §a").replace("Find the key", "找到钥匙").replace("Welcome", "欢迎"), rewritten)
    }
    @Test fun modernNestedExecute() {
        val source = "execute as @a[tag=quest] at @s if score @s phase matches 1.. run execute positioned ~ ~1 ~ run title @s actionbar Find the key"
        val plan = DisplayCommand.inspect(source)
        assertTrue(plan.issues.isEmpty(), plan.issues.joinToString())
        assertEquals("Find the key", plan.spans.single().text)
        assertEquals(source.replace("Find the key", "找到钥匙"), plan.rewrite(mapOf(0 to "找到钥匙")))
    }
    @Test fun legacyExecute() {
        val source = "execute @a ~ ~ ~ detect ~ ~-1 ~ stone 0 say Welcome"
        val plan = DisplayCommand.inspect(source)
        assertEquals(source.replace("Welcome", "欢迎"), plan.rewrite(mapOf(0 to "欢迎")))
    }
    @Test fun unknownExecuteRemainsUntouched() {
        val plan = DisplayCommand.inspect("execute store result score @s quest run say Hello")
        assertTrue(plan.spans.isEmpty()); assertTrue(plan.issues.isNotEmpty())
    }
    @Test fun unknownCommandDoesNotExposeIdentifiers() {
        val plan = DisplayCommand.inspect("scoreboard objectives add Hello dummy")
        assertTrue(plan.spans.isEmpty()); assertTrue(plan.issues.isNotEmpty())
    }
    @Test fun malformedRawtextRejected() {
        listOf("tellraw @a {\"rawtext\":[{\"text\":\"Hi\",\"text\":\"Bye\"}]}",
            "tellraw @a {\"rawtext\":[{\"text\":\"Hi\",\"selector\":\"@p\"}]}",
            "tellraw @a {\"rawtext\":[{\"text\":5}]}",
            "tellraw @a {\"rawtext\":[{\"text\":\"Hi\"}]} extra").forEach {
            val plan = DisplayCommand.inspect(it)
            assertTrue(plan.spans.isEmpty(), it); assertTrue(plan.issues.isNotEmpty(), it)
        }
    }
    @Test fun jsonEscapeIsPreservedOutsideEditedText() {
        val source = "tellraw @a {\"rawtext\":[{\"translate\":\"map.\\u0061\"},{\"text\":\"Hello\"}]}"
        assertEquals(source.replace("Hello", "你好"), DisplayCommand.inspect(source).rewrite(mapOf(0 to "你好")))
    }
    @Test fun newlineInjectionRejected() {
        assertFailsWith<IllegalArgumentException> { DisplayCommand.inspect("say Hello").rewrite(mapOf(0 to "你好\nkill @a")) }
    }
    @Test fun placeholdersRestored() {
        val original = "Welcome %1\$s, §a{{player}}! \\n"
        val protected = ProtectedText.protect(original)
        assertEquals(original.replace("Welcome", "欢迎"), protected.restore(protected.input.replace("Welcome", "欢迎")))
    }
    @Test fun placeholdersCannotDisappearDuplicateOrReorder() {
        val protected = ProtectedText.protect("Hello %s §a")
        assertFailsWith<IllegalArgumentException> { protected.restore("你好") }
        assertFailsWith<IllegalArgumentException> { protected.restore(protected.input + protected.mapping[0].first) }
        assertFailsWith<IllegalArgumentException> { protected.restore("${protected.mapping[1].first} ${protected.mapping[0].first}") }
        assertFailsWith<IllegalArgumentException> { DisplayCommand.inspect("say Hello %s").rewrite(mapOf(0 to "你好")) }
    }
    @Test fun npcSelectorsResourceIdsAndMacroVariablesProtected() {
        val text = "Hello @initiator @npc @a[tag=quest] minecraft:gold_ingot ${'$'}(player)"
        val protected = ProtectedText.protect(text)
        assertEquals(text.replace("Hello", "你好"), protected.restore(protected.input.replace("Hello", "你好")))
        assertEquals(5, protected.mapping.size)
    }
    @Test fun jsonFileSpansPreserveStructuralKeys() {
        val source = "{\"minecraft:npc_dialogue\":{\"scenes\":[{\"scene_tag\":\"quest\",\"text\":\"Welcome\",\"buttons\":[{\"name\":\"Start\",\"commands\":[\"/say Good luck\"]}]}]}}"
        val strings = DisplayCommand.jsonStrings(source)
        assertTrue(strings.any { it.path == listOf("minecraft:npc_dialogue", "scenes", "0", "text") && it.text == "Welcome" })
        assertTrue(strings.none { it.text == "minecraft:npc_dialogue" })
        assertFails { DisplayCommand.jsonStrings("{\"text\":\"A\",\"text\":\"B\"}") }
    }

    // Small typed binary payloads exercise parser rejection paths; they are not world fixtures.
    private fun nbt(vararg values: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        fun string(s: String) { val b = s.toByteArray(); out.write(b.size and 255); out.write(b.size ushr 8); out.write(b) }
        out.write(10); string("")
        for ((key, value) in values) { out.write(8); string(key); string(value) }
        out.write(0)
        return out.toByteArray()
    }
    @Test fun typedNbtEditPreservesOtherBytes() {
        val bytes = nbt("Text" to "Hello", "id" to "Sign", "Unrelated" to "Do not change")
        val result = LittleEndianNbt.parse(bytes).rewrite(mapOf(listOf("0", "Text") to "你好"))
        assertContentEquals(nbt("Text" to "你好", "id" to "Sign", "Unrelated" to "Do not change"), result)
        assertContentEquals(bytes, LittleEndianNbt.parse(bytes).rewrite(emptyMap()))
    }
    @Test fun nbtMultipleRoots() {
        val record = LittleEndianNbt.parse(nbt("Text" to "First") + nbt("Text" to "Second"))
        assertEquals(listOf(listOf("0", "Text"), listOf("1", "Text")), record.texts.map { it.path })
    }
    @Test fun nbtTruncationDuplicateAndUnknownRejected() {
        val bytes = nbt("Text" to "Hello")
        for (n in 0 until bytes.size) assertFails { LittleEndianNbt.parse(bytes.copyOf(n)) }
        assertFails { LittleEndianNbt.parse(nbt("Text" to "A", "Text" to "B")) }
        assertFails { LittleEndianNbt.parse(byteArrayOf(10, 0, 0, 99, 0, 0, 0)) }
    }
    @Test fun nbtLengthAndPathValidation() {
        val record = LittleEndianNbt.parse(nbt("Text" to "Hello"))
        assertFails { record.rewrite(mapOf(listOf("0", "missing") to "你好")) }
        assertFails { record.rewrite(mapOf(listOf("0", "Text") to "中".repeat(30_000))) }
        assertFails { LittleEndianNbt.parse(nbt("Text" to "Hello"), maxNodes = 1) }
    }

    private fun zip(root: File, entries: List<Pair<String, ByteArray>>): File {
        val file = File(root, "input.zip")
        ZipOutputStream(file.outputStream()).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
            }
        }
        return file
    }
    private fun inTemp(block: (File) -> Unit) {
        val root = Files.createTempDirectory("mcwt-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    @Test fun zipTraversalCannotCreateOutsideFiles() = inTemp { root ->
        for (path in listOf("../escaped", "/absolute", "C:/drive", "a/../../escape", "a\\escape")) {
            val archive = zip(root, listOf(path to byteArrayOf(1)))
            val work = File(root, "work")
            assertFails { SafeWorldArchive().extract(archive, work) }
            assertFalse(work.exists()); assertFalse(File(root, "escaped").exists())
        }
    }
    @Test fun zipDuplicateUnicodeAndCaseRejected() = inTemp { root ->
        for (names in listOf(listOf("A.txt", "a.txt"), listOf("é.txt", "e\u0301.txt"))) {
            val archive = zip(root, names.map { it to byteArrayOf(1) })
            val work = File(root, "work")
            assertFails { SafeWorldArchive().extract(archive, work) }
            assertFalse(work.exists())
        }
    }
    @Test fun zipFileCountAndDepthLimited() = inTemp { root ->
        val count = zip(root, (1..5).map { "$it.txt" to byteArrayOf(1) })
        assertFails { SafeWorldArchive(SafeWorldArchive.Limits(files = 4)).extract(count, File(root, "work")) }
        val depth = zip(root, listOf("a/b/c.txt" to byteArrayOf(1)))
        assertFails { SafeWorldArchive(SafeWorldArchive.Limits(depth = 2)).extract(depth, File(root, "work")) }
    }
    @Test fun zipExpandedSizeAndRatioLimited() = inTemp { root ->
        val archive = zip(root, listOf("large.txt" to ByteArray(256 * 1024)))
        assertFails { SafeWorldArchive(SafeWorldArchive.Limits(bytes = 100)).extract(archive, File(root, "work")) }
        assertFails { SafeWorldArchive(SafeWorldArchive.Limits(ratio = 2)).extract(archive, File(root, "work")) }
    }
    @Test fun zipCancellationCleansOnlyWorkCopy() = inTemp { root ->
        val archive = zip(root, listOf("hello.txt" to byteArrayOf(1)))
        val original = archive.readBytes()
        val work = File(root, "work")
        assertFailsWith<CancellationException> { SafeWorldArchive().extract(archive, work) { throw CancellationException() } }
        assertFalse(work.exists()); assertContentEquals(original, archive.readBytes())
    }
    @Test fun corruptArchiveRejected() = inTemp { root ->
        val archive = File(root, "bad.zip").apply { writeBytes(byteArrayOf(80, 75, 3, 4, 0)) }
        val work = File(root, "work")
        assertFails { SafeWorldArchive().extract(archive, work) }; assertFalse(work.exists())
    }
    @Test fun existingDestinationNeverDeleted() = inTemp { root ->
        val archive = zip(root, listOf("a.txt" to byteArrayOf(1)))
        val work = File(root, "work").apply { mkdir() }
        val keep = File(work, "keep").apply { writeText("user data") }
        assertFails { SafeWorldArchive().extract(archive, work) }; assertEquals("user data", keep.readText())
    }
    @Test fun defaultLargeFileCountRejectedBeforeExtraction() = inTemp { root ->
        val archive = zip(root, (0..20_000).map { "file-$it.txt" to byteArrayOf() })
        val work = File(root, "work")
        assertFails { SafeWorldArchive().extract(archive, work) }
        assertFalse(work.exists())
    }
    @Test fun corruptStoredEntryCrcRejected() = inTemp { root ->
        val archive = File(root, "crc.zip")
        val data = "hello".toByteArray()
        ZipOutputStream(archive.outputStream()).use { out ->
            val entry = ZipEntry("a.txt").apply {
                method = ZipEntry.STORED
                size = data.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(data) }.value
            }
            out.putNextEntry(entry); out.write(data); out.closeEntry()
        }
        val bytes = archive.readBytes()
        bytes[35] = (bytes[35].toInt() xor 1).toByte()
        archive.writeBytes(bytes)
        val work = File(root, "work")
        val failure = assertFails { SafeWorldArchive().extract(archive, work) }
        assertEquals("Corrupt ZIP entry", failure.message)
        assertFalse(work.exists())
    }
}
