package cn.mcworld.translator.engine

object NativeBridge {
    init { System.loadLibrary("world_native") }
    external fun openDb(path: String): Long
    external fun closeDb(handle: Long)
    external fun cursor(handle: Long): Long
    external fun cursorRange(handle: Long, startByte: Int, endByteExclusive: Int): Long
    external fun countRecords(handle: Long): Long
    external fun next(cursor: Long): Array<ByteArray?>?
    /** Up to 500 DB keys per call; [0] is null when none matched, [2] holds the scan count. */
    external fun nextTextCandidate(cursor: Long): Array<ByteArray?>?
    external fun closeCursor(cursor: Long)
    external fun write(handle: Long, keys: Array<ByteArray>, originals: Array<ByteArray>, replacements: Array<ByteArray>)
    external fun openSp(path: String): Long
    external fun closeSp(handle: Long)
    external fun encode(handle: Long, input: String): Array<String>
    external fun decode(handle: Long, pieces: Array<String>): String
}
