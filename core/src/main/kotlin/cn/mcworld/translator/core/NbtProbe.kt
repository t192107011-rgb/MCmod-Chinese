package cn.mcworld.translator.core

import java.io.File

/** Development validation utility; it performs explicit manual edits, never translation. */
fun main(args: Array<String>) {
    require(args.size == 4) { "Usage: NbtProbe input output path replacement" }
    val input = File(args[0])
    val output = File(args[1])
    require(!output.exists()) { "Refusing to overwrite output" }
    val record = LittleEndianNbt.parse(input.readBytes())
    val path = args[2].split('/')
    val text = record.texts.single { it.path == path }
    require(ProtectedText.protect(text.value).mapping.map { it.second } == ProtectedText.protect(args[3]).mapping.map { it.second }) { "Formatting or placeholders changed" }
    val rewritten = record.rewrite(mapOf(path to args[3]))
    require(output.createNewFile())
    output.writeBytes(rewritten)
    println("Typed NBT edit and reparse verified: ${path.joinToString("/")}")
}
