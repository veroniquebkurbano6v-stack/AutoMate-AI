package com.palmagent.app.kb

import java.io.BufferedReader
import java.io.InputStream

/**
 * BGE-small-zh 用的最小化 BERT WordPiece 分词器。
 * 中文按字切分（BERT-wwm 惯例），英文/数字走 WordPiece 子词，未知字符标 [UNK]。
 * 仅满足 SOP 检索单元（短文本）的嵌入需求，非通用 NLP 分词器。
 */
class BertTokenizer(vocabStream: InputStream) {

    private val vocab = HashMap<String, Int>(20000)
    private val unk = "[UNK]"
    private val cls = "[CLS]"
    private val sep = "[SEP]"

    init {
        BufferedReader(vocabStream.reader(Charsets.UTF_8)).use { r ->
            var i = 0
            var line = r.readLine()
            while (line != null) {
                if (line.isNotEmpty()) vocab[line] = i
                i++
                line = r.readLine()
            }
        }
    }

    private fun isChinese(c: Char) = c.code in 0x4E00..0x9FFF

    private fun wordpiece(word: String): List<String> {
        val tokens = mutableListOf<String>()
        if (word.isEmpty()) return tokens
        var start = 0
        while (start < word.length) {
            var end = word.length
            var hit: String? = null
            while (start < end) {
                val sub = if (start == 0) word.substring(start, end)
                          else "##" + word.substring(start, end)
                if (vocab.containsKey(sub)) { hit = sub; break }
                end--
            }
            if (hit == null) { tokens.add(unk); start++ }
            else { tokens.add(hit); start = end }
        }
        return tokens
    }

    fun encode(text: String, maxLength: Int = 512): EncodedInput {
        val raw = mutableListOf<String>()
        val chars = text.toCharArray()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            when {
                c.isWhitespace() -> i++
                isChinese(c) -> { raw.add(c.toString()); i++ }
                c.isLetterOrDigit() -> {
                    val sb = StringBuilder()
                    while (i < chars.size && chars[i].isLetterOrDigit() && !isChinese(chars[i])) {
                        sb.append(chars[i]); i++
                    }
                    raw.addAll(wordpiece(sb.toString().lowercase()))
                }
                else -> { raw.add(c.toString()); i++ }
            }
        }
        val clsId = vocab[cls] ?: 0
        val sepId = vocab[sep] ?: 0
        val unkId = vocab[unk] ?: 0
        val maxContent = maxLength - 2
        val truncated = if (raw.size > maxContent) raw.subList(0, maxContent) else raw

        val ids = ArrayList<Int>(truncated.size + 2)
        val mask = ArrayList<Int>(truncated.size + 2)
        val types = ArrayList<Int>(truncated.size + 2)
        ids.add(clsId); mask.add(1); types.add(0)
        for (t in truncated) { ids.add(vocab[t] ?: unkId); mask.add(1); types.add(0) }
        ids.add(sepId); mask.add(1); types.add(0)
        return EncodedInput(ids.toIntArray(), mask.toIntArray(), types.toIntArray())
    }

    data class EncodedInput(val inputIds: IntArray, val attentionMask: IntArray, val tokenTypeIds: IntArray)
}
