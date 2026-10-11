package cn.mcworld.translator.core

import java.io.File

fun archiveProbe(args: Array<String>) {
    require(args.size == 3) { "Usage: ArchiveProbe extract|export input output" }
    val archive = SafeWorldArchive()
    when (args[0]) {
        "extract" -> println(archive.extract(File(args[1]), File(args[2])).canonicalPath)
        "export" -> { archive.export(File(args[1]), File(args[2])); println("Archive export completed") }
        else -> error("Unknown archive probe action")
    }
}

object ArchiveProbe {
    @JvmStatic fun main(args: Array<String>) = archiveProbe(args)
}
