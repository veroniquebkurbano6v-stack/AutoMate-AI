package com.palmagent.app.kb

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.LongBuffer

/**
 * ONNX Runtime 加载 bge-small-zh INT8 模型，单条文本嵌入（512 维，L2 归一化）。
 * mean pooling + normalize，与 sentence-transformers BGE 配置一致。
 */
class OnnxEmbedder(
    modelPath: String,
    vocabPath: String,
    private val embedDim: Int = 512
) {
    companion object { private const val TAG = "OnnxEmbedder" }

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: BertTokenizer

    init {
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        session = env.createSession(File(modelPath).absolutePath, opts)
        tokenizer = BertTokenizer(File(vocabPath).inputStream())
        Log.i(TAG, "ONNX embedder loaded, dim=$embedDim")
    }

    fun embed(text: String): FloatArray {
        val enc = tokenizer.encode(text, 512)
        val seq = enc.inputIds.size
        val shape = longArrayOf(1, seq.toLong())

        val inputIds = OnnxTensor.createTensor(
            env, LongBuffer.wrap(enc.inputIds.map { it.toLong() }.toLongArray()), shape)
        val attn = OnnxTensor.createTensor(
            env, LongBuffer.wrap(enc.attentionMask.map { it.toLong() }.toLongArray()), shape)
        val types = OnnxTensor.createTensor(
            env, LongBuffer.wrap(enc.tokenTypeIds.map { it.toLong() }.toLongArray()), shape)

        val inputs = mapOf(
            "input_ids" to inputIds,
            "attention_mask" to attn,
            "token_type_ids" to types
        )
        val output = session.run(inputs)
        try {
            // last_hidden_state: [1, seq, hidden]
            @Suppress("UNCHECKED_CAST")
            val raw3d = output[0].value as Array<Array<FloatArray>>
            val tokenVecs = raw3d[0]  // [seq][hidden]

            val pooled = FloatArray(embedDim)
            var count = 0
            for (i in 0 until seq) {
                if (enc.attentionMask[i] == 1) {
                    val v = tokenVecs[i]
                    for (j in 0 until embedDim) pooled[j] += v[j]
                    count++
                }
            }
            if (count > 0) for (j in 0 until embedDim) pooled[j] /= count

            // L2 normalize
            var norm = 0.0
            for (v in pooled) norm += (v * v).toDouble()
            norm = kotlin.math.sqrt(norm)
            if (norm > 0) for (j in 0 until embedDim) pooled[j] = (pooled[j] / norm).toFloat()

            return pooled
        } finally {
            output.close()
            inputIds.close(); attn.close(); types.close()
        }
    }

    fun close() { session.close() }
}
