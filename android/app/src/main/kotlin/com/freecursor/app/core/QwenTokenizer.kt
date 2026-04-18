package com.freecursor.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max

class QwenTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val idToToken: Map<Int, String>,
    private val mergeRanks: Map<Pair<String, String>, Int>,
    private val unknownId: Int,
    private val bosId: Int?,
    private val eosIds: Set<Int>,
) {
    private val cache = HashMap<String, List<String>>()
    private val splitRegex = Regex("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+")

    private val byteEncoder: Map<Int, Char> = bytesToUnicode()
    private val byteDecoder: Map<Char, Int> = byteEncoder.entries.associate { (k, v) -> v to k }

    fun encode(text: String, maxLength: Int): List<Long> {
        val normalized = text.replace("\u0000", "")
        val ids = ArrayList<Int>(max(16, normalized.length / 2))

        bosId?.let { ids += it }

        splitRegex.findAll(normalized).forEach { match ->
            val token = match.value
            if (token.isEmpty()) {
                return@forEach
            }
            val encoded = byteEncode(token)
            val pieces = bpe(encoded)
            pieces.forEach { piece ->
                ids += (vocab[piece] ?: unknownId)
            }
        }

        val trimmed = if (ids.size > maxLength) {
            ids.takeLast(maxLength)
        } else {
            ids
        }

        return trimmed.map { it.toLong() }
    }

    fun decode(ids: List<Long>): String {
        if (ids.isEmpty()) {
            return ""
        }

        val textBuilder = StringBuilder()
        ids.forEach { idLong ->
            val id = idLong.toInt()
            if (eosIds.contains(id)) {
                return@forEach
            }
            val token = idToToken[id] ?: return@forEach
            textBuilder.append(token)
        }

        val bytes = ArrayList<Byte>(textBuilder.length)
        textBuilder.forEach { char ->
            val byteValue = byteDecoder[char]
            if (byteValue != null) {
                bytes += byteValue.toByte()
            } else {
                char.toString().toByteArray(StandardCharsets.UTF_8).forEach { b ->
                    bytes += b
                }
            }
        }

        return bytes.toByteArray().toString(StandardCharsets.UTF_8)
    }

    fun eosTokenIds(): Set<Long> = eosIds.map { it.toLong() }.toSet()

    private fun byteEncode(token: String): String {
        val bytes = token.toByteArray(StandardCharsets.UTF_8)
        return buildString(bytes.size) {
            bytes.forEach { b ->
                val unsigned = b.toInt() and 0xFF
                append(byteEncoder[unsigned] ?: '?')
            }
        }
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }

        var word = token.map { it.toString() }
        if (word.size == 1) {
            cache[token] = word
            return word
        }

        while (true) {
            val pairs = getPairs(word)
            if (pairs.isEmpty()) {
                break
            }

            val bestPair = pairs.minByOrNull { pair -> mergeRanks[pair] ?: Int.MAX_VALUE }
                ?: break

            if (!mergeRanks.containsKey(bestPair)) {
                break
            }

            val merged = ArrayList<String>(word.size)
            var index = 0
            while (index < word.size) {
                if (index < word.lastIndex &&
                    word[index] == bestPair.first &&
                    word[index + 1] == bestPair.second
                ) {
                    merged += word[index] + word[index + 1]
                    index += 2
                } else {
                    merged += word[index]
                    index += 1
                }
            }
            word = merged
            if (word.size == 1) {
                break
            }
        }

        cache[token] = word
        return word
    }

    private fun getPairs(tokens: List<String>): Set<Pair<String, String>> {
        if (tokens.size < 2) {
            return emptySet()
        }
        val pairs = LinkedHashSet<Pair<String, String>>()
        for (i in 0 until tokens.lastIndex) {
            pairs += (tokens[i] to tokens[i + 1])
        }
        return pairs
    }

    companion object {
        fun fromBundle(bundleDir: File): QwenTokenizer? {
            val tokenizerFile = File(bundleDir, "tokenizer.json")
            if (!tokenizerFile.exists()) {
                return null
            }

            val tokenizerJson = runCatching {
                JSONObject(tokenizerFile.readText(Charsets.UTF_8))
            }.getOrNull() ?: return null

            val model = tokenizerJson.optJSONObject("model") ?: return null
            val vocabObject = model.optJSONObject("vocab") ?: return null

            val vocab = LinkedHashMap<String, Int>(vocabObject.length())
            vocabObject.keys().forEach { key ->
                vocab[key] = vocabObject.optInt(key)
            }

            val idToToken = vocab.entries.associate { (token, id) -> id to token }

            val mergesArray = model.optJSONArray("merges") ?: JSONArray()
            val mergeRanks = HashMap<Pair<String, String>, Int>(mergesArray.length())
            for (i in 0 until mergesArray.length()) {
                val raw = mergesArray.optString(i, "").trim()
                if (raw.isBlank() || raw.startsWith("#")) {
                    continue
                }
                val parts = raw.split(' ')
                if (parts.size == 2) {
                    mergeRanks[parts[0] to parts[1]] = i
                }
            }

            val tokenizerConfigFile = File(bundleDir, "tokenizer_config.json")
            val tokenizerConfig = if (tokenizerConfigFile.exists()) {
                runCatching { JSONObject(tokenizerConfigFile.readText(Charsets.UTF_8)) }.getOrNull()
            } else {
                null
            }

            val generationConfigFile = File(bundleDir, "generation_config.json")
            val generationConfig = if (generationConfigFile.exists()) {
                runCatching { JSONObject(generationConfigFile.readText(Charsets.UTF_8)) }.getOrNull()
            } else {
                null
            }

            val unknownId = run {
                val unk = tokenizerConfig?.optString("unk_token")
                if (unk != null && vocab.containsKey(unk)) {
                    vocab.getValue(unk)
                } else {
                    vocab["<|endoftext|>"] ?: 0
                }
            }

            val bosId = run {
                val bosToken = tokenizerConfig?.optString("bos_token")
                if (bosToken != null && bosToken.isNotBlank() && vocab.containsKey(bosToken)) {
                    vocab[bosToken]
                } else {
                    null
                }
            }

            val eosSet = LinkedHashSet<Int>()

            val eosRawFromGen = generationConfig?.opt("eos_token_id")
            when (eosRawFromGen) {
                is Number -> eosSet += eosRawFromGen.toInt()
                is JSONArray -> {
                    for (i in 0 until eosRawFromGen.length()) {
                        val item = eosRawFromGen.opt(i)
                        if (item is Number) {
                            eosSet += item.toInt()
                        }
                    }
                }
            }

            val eosToken = tokenizerConfig?.optString("eos_token")
            if (!eosToken.isNullOrBlank() && vocab.containsKey(eosToken)) {
                eosSet += vocab.getValue(eosToken)
            }
            if (eosSet.isEmpty()) {
                vocab["<|im_end|>"]?.let { eosSet += it }
                vocab["<|endoftext|>"]?.let { eosSet += it }
            }

            return QwenTokenizer(
                vocab = vocab,
                idToToken = idToToken,
                mergeRanks = mergeRanks,
                unknownId = unknownId,
                bosId = bosId,
                eosIds = eosSet,
            )
        }

        private fun bytesToUnicode(): Map<Int, Char> {
            val bs = mutableListOf<Int>()
            bs += (33..126)
            bs += (161..172)
            bs += (174..255)

            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (!bs.contains(b)) {
                    bs += b
                    cs += 256 + n
                    n += 1
                }
            }

            return bs.zip(cs).associate { (b, c) -> b to c.toChar() }
        }
    }
}
