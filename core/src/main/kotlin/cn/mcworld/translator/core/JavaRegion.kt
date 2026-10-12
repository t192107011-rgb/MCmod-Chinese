package cn.mcworld.translator.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.DeflaterOutputStream

/** Reads Java Anvil region chunks independently; writes edits into new sectors. */
object JavaRegion {
    private const val SECTOR = 4096L
    private const val MAX_CHUNK = 32 * 1024 * 1024

    fun indices(file: File): List<Int> = RandomAccessFile(file, "r").use { raf ->
        require(raf.length() >= 2 * SECTOR && raf.length() <= 2L * 1024 * 1024 * 1024) { "Invalid region size" }
        (0 until 1024).filter { index -> raf.seek(index * 4L); raf.readInt() != 0 }
    }

    fun read(file: File, index: Int): ByteArray? = RandomAccessFile(file, "r").use { raf ->
        require(index in 0 until 1024 && raf.length() >= 2 * SECTOR) { "Invalid region header" }
        raf.seek(index * 4L)
        val location = raf.readInt()
        if (location == 0) return@use null
        val sector = location ushr 8
        val sectors = location and 255
        require(sector >= 2 && sectors > 0 && sector.toLong() * SECTOR + sectors * SECTOR <= raf.length()) { "Invalid chunk location" }
        raf.seek(sector.toLong() * SECTOR)
        val length = raf.readInt()
        require(length in 2..minOf(MAX_CHUNK, sectors * SECTOR.toInt() - 4)) { "Invalid chunk length" }
        val compression = raf.readUnsignedByte()
        require(compression and 0x80 == 0) { "External Java region chunks are not supported" }
        val compressed = ByteArray(length - 1)
        raf.readFully(compressed)
        val source = when (compression) {
            1 -> GZIPInputStream(ByteArrayInputStream(compressed))
            2 -> InflaterInputStream(ByteArrayInputStream(compressed))
            3 -> ByteArrayInputStream(compressed)
            else -> error("Unsupported Java chunk compression $compression")
        }
        source.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_CHUNK) { "Java chunk exceeds 32 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    fun rewrite(file: File, index: Int, original: ByteArray, changed: ByteArray) {
        require(read(file, index)?.contentEquals(original) == true) { "Java chunk changed since extraction" }
        require(changed.size <= MAX_CHUNK)
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(changed) }
        }.toByteArray()
        val length = compressed.size + 1
        val sectors = ((length + 4L + SECTOR - 1) / SECTOR).toInt()
        require(sectors in 1..255) { "Translated Java chunk exceeds region sector limit" }
        RandomAccessFile(file, "rw").use { raf ->
            val start = (raf.length() + SECTOR - 1) / SECTOR
            require(start in 2L..0xFFFFFFL && start + sectors <= 0x1000000L &&
                (start + sectors) * SECTOR <= 2L * 1024 * 1024 * 1024) { "Java region offset limit" }
            raf.setLength((start + sectors) * SECTOR)
            raf.seek(start * SECTOR)
            raf.writeInt(length)
            raf.writeByte(2)
            raf.write(compressed)
            raf.seek(index * 4L)
            raf.writeInt((start.toInt() shl 8) or sectors)
            raf.fd.sync()
        }
        require(read(file, index)?.contentEquals(changed) == true) { "Java chunk verification failed" }
    }
}
