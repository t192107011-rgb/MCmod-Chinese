package cn.mcworld.translator.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.GZIPInputStream
import java.io.ByteArrayOutputStream

class SafeWorldArchive(private val limits: Limits = Limits()) {
    enum class Edition { BEDROCK, JAVA }
    data class Limits(
        val bytes: Long = 2L * 1024 * 1024 * 1024,
        val files: Int = 20_000,
        val ratio: Long = 200,
        val depth: Int = 32,
    )
    init { require(limits.bytes > 0 && limits.files > 0 && limits.ratio > 0 && limits.depth > 0) }

    /** Caller owns the input. Only a new destination is ever created or removed. */
    fun extract(input: File, destination: File, progress: (Long) -> Unit = {}, checkCancellation: () -> Unit = {}): File {
        require(!destination.exists() && destination.mkdir()) { "Work destination already exists or cannot be created" }
        try {
            var total = 0L
            val seen = mutableSetOf<String>()
            ZipFile(input, Charsets.UTF_8).use { zip ->
                require(zip.size() in 1..limits.files) { "Archive file-count limit" }
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    checkCancellation()
                    val entry = entries.nextElement()
                    val name = entry.name.removeSuffix("/")
                    require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && !name.contains(':')) { "Unsafe ZIP path" }
                    val parts = name.split('/')
                    require(parts.size <= limits.depth && parts.none { it.isEmpty() || it == "." || it == ".." || it.any { c -> c.code < 32 } }) { "Unsafe ZIP path or depth" }
                    val normalized = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                    require(seen.add(normalized)) { "Duplicate ZIP filename" }
                    val target = File(destination, name)
                    require(target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) { "ZIP path traversal" }
                    if (entry.isDirectory) {
                        require(target.isDirectory || target.mkdirs()) { "Cannot create directory" }
                        continue
                    }
                    require(entry.size >= 0 && entry.compressedSize >= 0 && entry.crc >= 0) { "Missing ZIP metadata" }
                    val compressed = maxOf(1L, entry.compressedSize)
                    require(entry.compressedSize <= input.length()) { "Invalid ZIP compressed size" }
                    val quotient = entry.size / compressed
                    require(entry.size <= limits.bytes && (quotient < limits.ratio || quotient == limits.ratio && entry.size % compressed == 0L)) { "ZIP size or compression-ratio limit" }
                    require(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "Cannot create parent directory" }
                    require(target.createNewFile()) { "File/directory collision" }
                    val crc = CRC32()
                    var actual = 0L
                    zip.getInputStream(entry).use { source ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkCancellation()
                                val count = source.read(buffer)
                                if (count < 0) break
                                actual += count
                                total += count
                                progress(total)
                                require(total <= limits.bytes && actual <= entry.size) { "ZIP expanded-size limit" }
                                crc.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    require(actual == entry.size && crc.value == entry.crc) { "Corrupt ZIP entry" }
                }
            }
            return worldRoot(destination)
        } catch (failure: Throwable) {
            destination.deleteRecursively()
            throw failure
        }
    }

    fun worldRoot(directory: File): File {
        var current = directory
        repeat(limits.depth) {
            val children = current.listFiles() ?: error("Cannot list world folder")
            require(children.none { Files.isSymbolicLink(it.toPath()) }) { "Symbolic links are unsupported" }
            if (File(current, "level.dat").isFile) {
                edition(current)
                return current
            }
            require(children.size == 1 && children[0].isDirectory) { "Cannot locate a unique world root" }
            current = children[0]
        }
        error("World wrapper depth limit")
    }

    fun edition(world: File): Edition {
        val level = File(world, "level.dat")
        require(level.isFile) { "Missing level.dat" }
        if (File(world, "db/CURRENT").isFile && File(world, "db").isDirectory) {
            validateLevelDat(level)
            return Edition.BEDROCK
        }
        require(File(world, "region").isDirectory) { "Not a recognized Java or Bedrock world" }
        require(level.length() in 16..(32L * 1024 * 1024)) { "Invalid Java level.dat size" }
        GZIPInputStream(level.inputStream()).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 32 * 1024 * 1024) { "Java level.dat too large" }
                output.write(buffer, 0, count)
            }
            BigEndianNbt.parse(output.toByteArray())
        }
        return Edition.JAVA
    }

    private fun validateLevelDat(file: File) {
        require(file.length() in 9..(32L * 1024 * 1024 + 8)) { "Invalid level.dat size" }
        val bytes = file.readBytes()
        fun intAt(offset: Int) = (0..3).fold(0) { n, i -> n or ((bytes[offset + i].toInt() and 255) shl (i * 8)) }
        require(intAt(0) in 8..10) { "Unsupported Bedrock level.dat storage version" }
        require(intAt(4) == bytes.size - 8) { "Invalid level.dat payload length" }
        LittleEndianNbt.parse(bytes.copyOfRange(8, bytes.size))
    }

    /** Database must have been closed and revalidated by the caller before this operation. */
    fun export(world: File, output: File, progress: (Long) -> Unit = {}, checkCancellation: () -> Unit = {}) {
        require(worldRoot(world).canonicalFile == world.canonicalFile) { "Export requires normalized world root" }
        require(!output.canonicalPath.startsWith(world.canonicalPath + File.separator)) { "Output cannot be inside the world" }
        require(output.createNewFile()) { "Export destination must be new" }
        try {
            var files = 0
            var total = 0L
            ZipOutputStream(output.outputStream(), Charsets.UTF_8).use { zip ->
                fun visit(directory: File, depth: Int) {
                    require(depth <= limits.depth) { "Export path depth limit" }
                    val children = directory.listFiles() ?: error("Cannot list export directory")
                    for (file in children.sortedBy { it.name }) {
                        checkCancellation()
                        require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links are unsupported" }
                        val path = file.relativeTo(world).invariantSeparatorsPath
                        require(++files <= limits.files) { "Export file-count limit" }
                        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            zip.putNextEntry(ZipEntry("$path/")); zip.closeEntry()
                            visit(file, depth + 1)
                        } else {
                            require(file.isFile) { "Unsupported file type" }
                            zip.putNextEntry(ZipEntry(path))
                            file.inputStream().use { source ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    checkCancellation()
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= limits.bytes) { "Export size limit" }
                                    progress(total)
                                    zip.write(buffer, 0, count)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                visit(world, 0)
            }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }
}
