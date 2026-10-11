package cn.mcworld.translator.core

/** Keep substitutions and formatting intact. An invalid model result is never accepted. */
object ProtectedText {
    private val tokens = Regex("[\\r\\n]+|§[0-9a-zA-Z]|@[A-Za-z_][A-Za-z0-9_]*(?:\\[[^\\]\\r\\n]*\\])?|[a-z0-9_.-]+:[a-z0-9_./-]+|%(?:[0-9]+\\$)?[-#+ 0,(]*[0-9]*(?:\\.[0-9]+)?[a-zA-Z%]|\\{\\{[^{}\\r\\n]+\\}\\}|\\{[A-Za-z0-9_.:-]+\\}|\\$\\{[^{}\\r\\n]+\\}|\\$\\([A-Za-z0-9_.:-]+\\)|\\\\(?:[nrtbf\\\\\"']|u[0-9a-fA-F]{4})")
    data class Protected(val original: String, val input: String, val mapping: List<Pair<String, String>>) {
        fun restore(translated: String): String {
            require(mapping.all { (marker, _) -> translated.windowed(marker.length).count { it == marker } == 1 }) { "Placeholder missing or duplicated" }
            require(Regex("__MCWT_[0-9]+__").findAll(translated).map { it.value }.toList() == mapping.map { it.first }) { "Placeholder sequence changed" }
            var result = translated
            mapping.forEach { (marker, original) -> result = result.replace(marker, original) }
            require(tokens.findAll(result).map { it.value }.toList() == tokens.findAll(original).map { it.value }.toList()) { "Placeholder validation failed" }
            return result
        }
    }
    fun protect(text: String): Protected {
        require(!text.contains("__MCWT_")) { "Reserved placeholder marker in source" }
        val mapping = mutableListOf<Pair<String, String>>()
        val input = tokens.replace(text) {
            val marker = "__MCWT_${mapping.size}__"
            mapping += marker to it.value
            marker
        }
        return Protected(text, input, mapping)
    }
}
