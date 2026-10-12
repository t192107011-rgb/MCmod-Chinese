package cn.mcworld.translator.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Disk NBT only, not network/varint NBT. Unchanged bytes are preserved verbatim. */
object LittleEndianNbt {
    data class Text(val path: List<String>, val value: String, val offset: Int, val end: Int)
    data class Record(val bytes: ByteArray, val texts: List<Text>, val types: Map<List<String>, Int>) {
        fun rewrite(changes: Map<List<String>, String>): ByteArray {
            require(changes.keys.all { p -> texts.count { it.path == p } == 1 }) { "Unknown or ambiguous NBT path" }
            val output = ByteArrayOutputStream()
            var cursor = 0
            for (text in texts.sortedBy { it.offset }) {
                val replacement = changes[text.path] ?: continue
                val encoded = encode(replacement)
                require(encoded.size <= 65535) { "NBT string exceeds unsigned short length" }
                output.write(bytes, cursor, text.offset - cursor)
                output.write(encoded.size and 255)
                output.write(encoded.size ushr 8)
                output.write(encoded)
                cursor = text.end
            }
            output.write(bytes, cursor, bytes.size - cursor)
            val result = output.toByteArray()
            val verified = parse(result)
            for ((path, expected) in changes) {
                require(verified.texts.single { it.path == path }.value == expected) { "NBT verification failed" }
            }
            return result
        }
    }

    fun parse(bytes: ByteArray, maxDepth: Int = 64, maxNodes: Int = 1_000_000, copyBytes: Boolean = true): Record {
        require(bytes.size <= 32 * 1024 * 1024) { "NBT record too large" }
        val parser = Parser(bytes, maxDepth, maxNodes)
        var root = 0
        while (parser.position < bytes.size) {
            require(parser.u8() == 10) { "Expected compound disk NBT root" }
            parser.string() // root name is structural; never offered for translation
            parser.payload(10, listOf(root++.toString()), 0)
        }
        require(root > 0) { "Empty NBT record" }
        return Record(if (copyBytes) bytes.copyOf() else bytes, parser.texts.toList(), parser.types.toMap())
    }

    private fun encode(value: String): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private class Parser(val bytes: ByteArray, val maxDepth: Int, val maxNodes: Int) {
        var position = 0
        var nodes = 0
        val texts = mutableListOf<Text>()
        val types = mutableMapOf<List<String>, Int>()
        fun skip(count: Int) {
            require(count >= 0 && count <= bytes.size - position) { "Truncated NBT at $position" }
            position += count
        }
        fun u8(): Int { val p = position; skip(1); return bytes[p].toInt() and 255 }
        fun i32(): Int {
            var result = 0
            repeat(4) { result = result or (u8() shl (8 * it)) }
            return result
        }
        fun string(): String {
            val size = u8() or (u8() shl 8)
            val start = position
            skip(size)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, size)).toString()
        }
        fun payload(type: Int, path: List<String>, depth: Int) {
            require(depth <= maxDepth && ++nodes <= maxNodes) { "NBT complexity limit" }
            types[path] = type
            when (type) {
                1 -> skip(1)
                2 -> skip(2)
                3, 5 -> skip(4)
                4, 6 -> skip(8)
                7, 11, 12 -> {
                    val count = i32()
                    val unit = when (type) { 11 -> 4; 12 -> 8; else -> 1 }
                    require(count >= 0 && count <= (bytes.size - position) / unit) { "Invalid NBT array length" }
                    skip(count * unit)
                }
                8 -> {
                    val start = position
                    val value = string()
                    texts += Text(path, value, start, position)
                }
                9 -> {
                    val elementType = u8()
                    val count = i32()
                    require(count >= 0 && count <= maxNodes - nodes) { "Invalid NBT list length" }
                    require(elementType in 1..12 || elementType == 0 && count == 0) { "Invalid NBT list type" }
                    repeat(count) { payload(elementType, path + it.toString(), depth + 1) }
                }
                10 -> {
                    val names = mutableSetOf<String>()
                    while (true) {
                        val childType = u8()
                        if (childType == 0) break
                        require(childType in 1..12) { "Unknown NBT tag $childType" }
                        val name = string()
                        require(names.add(name)) { "Duplicate NBT compound key" }
                        payload(childType, path + name, depth + 1)
                    }
                }
                else -> error("Unsupported NBT tag $type")
            }
        }
    }
}
