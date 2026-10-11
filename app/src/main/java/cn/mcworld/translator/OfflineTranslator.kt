package cn.mcworld.translator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import cn.mcworld.translator.core.ProtectedText
import cn.mcworld.translator.engine.NativeBridge
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest

/** All graphs, vocabularies, tokenizers and native inference are packaged in the APK. */
class OfflineTranslator(private val context: Context) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private var loaded: Model? = null
    private val manifest = JSONArray(context.assets.open("model-manifest.json").bufferedReader().use { it.readText() })

    suspend fun translate(text: String, language: String): String {
        require(language in listOf("en", "ru", "de", "fr", "es")) { "不支持的原文语言" }
        val protected = ProtectedText.protect(text)
        val marker = Regex("__MCWT_[0-9]+__")
        val output = StringBuilder()
        var cursor = 0
        suspend fun appendSegment(segment: String) {
            currentCoroutineContext().ensureActive()
            if (segment.isBlank() || segment.none { it.isLetter() }) { output.append(segment); return }
            val leading = segment.takeWhile { it.isWhitespace() }
            val trailing = segment.takeLastWhile { it.isWhitespace() }
            var translated = segment.trim()
            if (language != "en") translated = model("$language-en").generate(translated)
            translated = model("en-zh").generate(translated)
            require(translated.any { it in '\u4e00'..'\u9fff' }) { "模型输出没有中文，保留原文" }
            output.append(leading).append(translated).append(trailing)
        }
        for (match in marker.findAll(protected.input)) {
            appendSegment(protected.input.substring(cursor, match.range.first))
            output.append(match.value)
            cursor = match.range.last + 1
        }
        appendSegment(protected.input.substring(cursor))
        return protected.restore(output.toString())
    }

    private suspend fun model(pair: String): Model {
        if (loaded?.pair == pair) return loaded!!
        loaded?.close(); loaded = null
        var record: JSONObject? = null
        for (i in 0 until manifest.length()) if (manifest.getJSONObject(i).getString("pair") == pair) record = manifest.getJSONObject(i)
        val files = requireNotNull(record).getJSONArray("files")
        val folder = File(context.noBackupFilesDir, "models/$pair").apply { mkdirs() }
        val required = setOf("onnx/encoder_model_quantized.onnx", "onnx/decoder_model_quantized.onnx", "source.spm", "target.spm", "vocab.json", "config.json")
        for (i in 0 until files.length()) {
            currentCoroutineContext().ensureActive()
            val file = files.getJSONObject(i)
            val name = file.getString("file")
            if (name !in required) continue
            val destination = File(folder, name)
            val expected = file.getString("sha256")
            fun digest(path: File): String {
                val hash = MessageDigest.getInstance("SHA-256")
                path.inputStream().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = source.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                }
                return hash.digest().joinToString("") { "%02x".format(it) }
            }
            if (destination.exists() && destination.length() == file.getLong("bytes") && digest(destination) == expected) continue
            destination.parentFile!!.mkdirs()
            val temporary = File(destination.parentFile, destination.name + ".part")
            try {
                context.assets.open("models/$pair/$name").use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(temporary.length() == file.getLong("bytes") && digest(temporary) == expected) { "内置模型校验失败" }
                require(!destination.exists() || destination.delete())
                require(temporary.renameTo(destination))
            } finally { temporary.delete() }
        }
        return Model(pair, folder, environment).also { loaded = it }
    }

    override fun close() { loaded?.close(); loaded = null }

    private class Model(val pair: String, folder: File, environment: OrtEnvironment) : Closeable {
        private val config = JSONObject(File(folder, "config.json").readText())
        private val vocab = JSONObject(File(folder, "vocab.json").readText()).let { json -> json.keys().asSequence().associateWith { json.getInt(it) } }
        private val reverse = vocab.entries.associate { it.value to it.key }
        private val source = NativeBridge.openSp(File(folder, "source.spm").absolutePath)
        private var target = 0L
        private var encoder: OrtSession? = null
        private var decoder: OrtSession? = null
        private val env = environment
        init {
            try {
                target = NativeBridge.openSp(File(folder, "target.spm").absolutePath)
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                    encoder = env.createSession(File(folder, "onnx/encoder_model_quantized.onnx").absolutePath, options)
                    decoder = env.createSession(File(folder, "onnx/decoder_model_quantized.onnx").absolutePath, options)
                }
                require(encoder!!.inputNames == setOf("input_ids", "attention_mask") &&
                    decoder!!.inputNames == setOf("input_ids", "encoder_attention_mask", "encoder_hidden_states")) { "不支持的模型推理接口" }
            } catch (failure: Throwable) { close(); throw failure }
        }
        suspend fun generate(text: String): String {
            val ids = mutableListOf<Long>()
            if (pair == "en-zh") ids += requireNotNull(vocab[">>cmn_Hans<<"]).toLong()
            ids += NativeBridge.encode(source, text).map { (vocab[it] ?: vocab.getValue("<unk>")).toLong() }
            ids += config.getInt("eos_token_id").toLong()
            require(ids.size <= 256) { "文本超过 256 个词元，请手动拆分或保留原文" }
            val shape = longArrayOf(1, ids.size.toLong())
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids.toLongArray()), shape).use { input ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape).use { mask ->
                    encoder!!.run(mapOf("input_ids" to input, "attention_mask" to mask), setOf("last_hidden_state")).use { encoded ->
                        val state = encoded.get("last_hidden_state").get() as OnnxTensor
                        val generated = mutableListOf(config.getInt("decoder_start_token_id").toLong())
                        repeat(128) {
                            currentCoroutineContext().ensureActive()
                            OnnxTensor.createTensor(env, LongBuffer.wrap(generated.toLongArray()), longArrayOf(1, generated.size.toLong())).use { tokens ->
                                decoder!!.run(mapOf("input_ids" to tokens, "encoder_attention_mask" to mask, "encoder_hidden_states" to state), setOf("logits")).use { output ->
                                    val tensor = output.get("logits").get() as OnnxTensor
                                    val logits = tensor.floatBuffer
                                    val size = config.getInt("vocab_size")
                                    val offset = (generated.size - 1) * size
                                    var best = -1
                                    var score = Float.NEGATIVE_INFINITY
                                    for (id in 0 until size) {
                                        if (id == config.getInt("pad_token_id")) continue
                                        val value = logits[offset + id]
                                        if (value > score) { score = value; best = id }
                                    }
                                    require(best >= 0) { "无效的模型输出" }
                                    if (best == config.getInt("eos_token_id")) {
                                        require(generated.drop(1).none { it == vocab.getValue("<unk>").toLong() }) { "模型产生未知词元" }
                                        val result = NativeBridge.decode(target, generated.drop(1).map { reverse.getValue(it.toInt()) }.toTypedArray())
                                        require(result.isNotBlank()) { "模型输出为空" }
                                        return result
                                    }
                                    generated += best.toLong()
                                }
                            }
                        }
                    }
                }
            }
            error("模型生成未正常结束，保留原文")
        }
        override fun close() {
            decoder?.close(); decoder = null
            encoder?.close(); encoder = null
            if (target != 0L) { NativeBridge.closeSp(target); target = 0 }
            NativeBridge.closeSp(source)
        }
    }
}
