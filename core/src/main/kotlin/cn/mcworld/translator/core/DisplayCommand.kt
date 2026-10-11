package cn.mcworld.translator.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Returns exact editable source spans; unknown command grammar is never guessed. */
object DisplayCommand {
    data class JsonString(val path: List<String>, val start: Int, val end: Int, val text: String)
    fun jsonStrings(source: String): List<JsonString> {
        require(source.length <= 8 * 1024 * 1024) { "JSON file too large" }
        val root = JsonSpans(source, 0).parse(0)
        require(source.substring(root.end).isBlank())
        Json.parseToJsonElement(source)
        val strings = mutableListOf<JsonString>()
        fun visit(node: Node, path: List<String>) {
            node.string?.let { strings += JsonString(path, node.start, node.end, it) }
            node.fields?.forEach { (key, child) -> visit(child, path + key) }
            node.items?.forEachIndexed { index, child -> visit(child, path + index.toString()) }
        }
        visit(root, emptyList())
        return strings
    }
    data class Span(val start: Int, val end: Int, val text: String, val json: Boolean = false)
    data class Plan(val source: String, val spans: List<Span>, val issues: List<String>) {
        fun rewrite(replacements: Map<Int, String>): String {
            require(replacements.keys.all { it in spans.indices })
            val result = StringBuilder(source)
            for ((index, span) in spans.withIndex().toList().asReversed()) {
                val replacement = replacements[index] ?: continue
                // Also validate manual edits, before any command is written back.
                val protected = ProtectedText.protect(span.text)
                require(ProtectedText.protect(replacement).mapping.map { it.second } == protected.mapping.map { it.second }) { "Protected token changed" }
                require(span.json || replacement.none { it == '\r' || it == '\n' }) { "A command cannot gain a new line" }
                result.replace(span.start, span.end, if (span.json) JsonPrimitive(replacement).toString() else replacement)
            }
            val text = result.toString()
            val check = inspect(text)
            require(check.spans.size == spans.size && check.issues == issues) { "Command structure changed" }
            return text
        }
    }
    private data class Token(val start: Int, val end: Int, val text: String)

    fun inspect(command: String): Plan {
        require(command.length <= 1024 * 1024 && '\n' !in command && '\r' !in command) { "Invalid command length or line" }
        val spans = mutableListOf<Span>()
        val issues = mutableListOf<String>()
        try { inspectAt(command, 0, 0, spans, issues) }
        catch (e: IllegalArgumentException) { spans.clear(); issues += e.message ?: "Unsupported command" }
        return Plan(command, spans.toList(), issues.toList())
    }

    private fun inspectAt(source: String, offset: Int, depth: Int, spans: MutableList<Span>, issues: MutableList<String>) {
        require(depth <= 16) { "Nested execute depth limit" }
        val tokens = tokenize(source, offset)
        require(tokens.isNotEmpty()) { "Empty command" }
        val verb = tokens[0].text.removePrefix("/")
        fun tail(index: Int, json: Boolean) {
            require(index < tokens.size) { "Missing displayed text" }
            val start = tokens[index].start
            if (json) rawtext(source, start, spans, issues)
            else spans += Span(start, source.length, source.substring(start))
        }
        when (verb) {
            "say" -> tail(1, false)
            "tell", "msg", "w" -> { require(tokens.size >= 3); tail(2, false) }
            "tellraw" -> { require(tokens.size >= 3); tail(2, true) }
            "title", "titleraw" -> {
                require(tokens.size >= 3) { "Incomplete title command" }
                when (tokens[2].text) {
                    "title", "subtitle", "actionbar" -> tail(3, verb == "titleraw")
                    "clear", "reset", "times" -> issues += "No display text in title control command"
                    else -> issues += "Unsupported title action"
                }
            }
            "execute" -> {
                var i = 1
                require(i < tokens.size) { "Incomplete execute" }
                // Legacy Bedrock execute <selector> <x> <y> <z> [detect ...] <command>.
                if (tokens[i].text.startsWith("@")) {
                    i += 4
                    require(i < tokens.size) { "Incomplete legacy execute" }
                    if (tokens[i].text == "detect") i += 6
                } else {
                    while (true) {
                        require(i < tokens.size) { "Execute has no run command" }
                        val part = tokens[i++].text
                        if (part == "run") break
                        val argumentCount = when (part) {
                            "as", "at", "in", "anchored", "align" -> 1
                            "positioned" -> if (tokens.getOrNull(i)?.text == "as") 2 else 3
                            "rotated" -> 2
                            "facing" -> if (tokens.getOrNull(i)?.text == "entity") 3 else 3
                            "if", "unless" -> {
                                require(i < tokens.size) { "Incomplete execute condition" }
                                when (tokens[i++].text) {
                                    "entity" -> 1
                                    "block" -> 4 + if (tokens.getOrNull(i + 4)?.text?.startsWith("[") == true) 1 else 0
                                    "blocks" -> 10
                                    "score" -> {
                                        val op = tokens.getOrNull(i + 2)?.text
                                        require(op in listOf("matches", "=", "<", ">", "<=", ">=")) { "Unsupported score condition" }
                                        if (op == "matches") 4 else 5
                                    }
                                    else -> throw IllegalArgumentException("Unsupported execute condition")
                                }
                            }
                            else -> throw IllegalArgumentException("Unsupported execute subcommand: $part")
                        }
                        i += argumentCount
                    }
                }
                require(i < tokens.size) { "Execute has no nested command" }
                inspectAt(source, tokens[i].start, depth + 1, spans, issues)
            }
            else -> issues += "Unsupported command: $verb"
        }
    }

    private fun tokenize(source: String, offset: Int): List<Token> {
        val result = mutableListOf<Token>()
        var i = offset
        while (i < source.length) {
            if (source[i].isWhitespace()) { i++; continue }
            val start = i
            val stack = ArrayDeque<Char>()
            var quoted = false
            while (i < source.length) {
                val c = source[i]
                if (quoted && c == '\\') { require(i + 1 < source.length); i += 2; continue }
                if (c == '"') quoted = !quoted
                if (!quoted) {
                    when (c) {
                        '[', '{' -> { require(stack.size < 64); stack.addLast(c) }
                        ']', '}' -> require(stack.removeLastOrNull() == if (c == ']') '[' else '{') { "Unbalanced command structure" }
                    }
                    if (c.isWhitespace() && stack.isEmpty()) break
                }
                i++
            }
            require(!quoted && stack.isEmpty()) { "Unclosed command string or structure" }
            result += Token(start, i, source.substring(start, i))
        }
        return result
    }

    private data class Node(val start: Int, val end: Int, val string: String? = null, val fields: Map<String, Node>? = null, val items: List<Node>? = null)
    private fun rawtext(source: String, start: Int, spans: MutableList<Span>, issues: MutableList<String>) {
        val parser = JsonSpans(source, start)
        val root = parser.parse(0)
        require(source.substring(root.end).isBlank()) { "Trailing rawtext arguments" }
        fun visit(node: Node) {
            val fields = node.fields ?: throw IllegalArgumentException("rawtext component must be an object")
            if (fields.keys == setOf("rawtext")) {
                (fields.getValue("rawtext").items ?: throw IllegalArgumentException("rawtext must be an array")).forEach { visit(it) }
                return
            }
            val kinds = fields.keys.intersect(setOf("text", "translate", "selector", "score"))
            require(kinds.size == 1) { "Ambiguous rawtext component" }
            when (kinds.single()) {
                "text" -> {
                    require(fields.keys == setOf("text")) { "Unknown text component fields" }
                    val text = fields.getValue("text")
                    require(text.string != null) { "text must be a string" }
                    spans += Span(text.start, text.end, text.string, true)
                }
                "translate" -> {
                    require(fields.getValue("translate").string != null) { "translate key must be a string" }
                    require(fields.keys.all { it == "translate" || it == "with" }) { "Unknown translate fields" }
                    fields["with"]?.let { with ->
                        if (with.items != null) with.items.forEach { arg ->
                            if (arg.string != null) spans += Span(arg.start, arg.end, arg.string, true)
                            else if (arg.fields?.containsKey("rawtext") == true) visit(arg)
                            else issues += "Unsupported with argument retained"
                        } else if (with.fields?.containsKey("rawtext") == true) visit(with)
                        else issues += "Unsupported with structure retained"
                    }
                }
                "selector", "score" -> { /* Entire structure stays byte-identical. */ }
            }
        }
        visit(root)
        // Kotlin serialization independently validates JSON syntax after bounded parsing.
        Json.parseToJsonElement(source.substring(start))
    }
    private class JsonSpans(val source: String, var p: Int) {
        fun whitespace() { while (p < source.length && source[p] in " \t\r\n") p++ }
        fun string(): Node {
            val start = p++
            while (p < source.length) {
                if (source[p] == '\\') { p += 2; continue }
                if (source[p++] == '"') {
                    val value = Json.parseToJsonElement(source.substring(start, p)).jsonPrimitive.content
                    return Node(start, p, string = value)
                }
            }
            throw IllegalArgumentException("Unclosed JSON string")
        }
        fun parse(depth: Int): Node {
            require(depth <= 64) { "JSON depth limit" }
            whitespace()
            val start = p
            require(p < source.length) { "Truncated JSON" }
            if (source[p] == '"') return string()
            if (source[p] == '{') {
                p++; whitespace()
                val fields = linkedMapOf<String, Node>()
                if (p < source.length && source[p] == '}') { p++; return Node(start, p, fields = fields) }
                while (true) {
                    require(p < source.length && source[p] == '"') { "Invalid JSON object key" }
                    val key = string().string!!
                    whitespace(); require(p < source.length && source[p++] == ':')
                    require(!fields.containsKey(key)) { "Duplicate JSON key" }
                    fields[key] = parse(depth + 1)
                    whitespace(); require(p < source.length)
                    val separator = source[p++]
                    if (separator == '}') break
                    require(separator == ','); whitespace()
                }
                return Node(start, p, fields = fields)
            }
            if (source[p] == '[') {
                p++; whitespace()
                val items = mutableListOf<Node>()
                if (p < source.length && source[p] == ']') { p++; return Node(start, p, items = items) }
                while (true) {
                    items += parse(depth + 1); whitespace(); require(p < source.length)
                    val separator = source[p++]
                    if (separator == ']') break
                    require(separator == ',')
                }
                return Node(start, p, items = items)
            }
            while (p < source.length && source[p] !in ",]} \t\r\n") p++
            require(p > start) { "Invalid JSON value" }
            return Node(start, p)
        }
    }
}
