package com.palmagent.app.channel.wechat

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object WeChatCdn {

    private const val UPLOAD_MAX_RETRIES = 3

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun encryptAesEcb(plaintext: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plaintext)
    }

    fun decryptAesEcb(ciphertext: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(ciphertext)
    }

    fun aesEcbPaddedSize(plaintextSize: Int): Int = ((plaintextSize + 1 + 15) / 16) * 16

    fun buildCdnUploadUrl(cdnBaseUrl: String, uploadParam: String, filekey: String): String =
        "$cdnBaseUrl/upload?encrypted_query_param=${
            java.net.URLEncoder.encode(uploadParam, "UTF-8")
        }&filekey=${java.net.URLEncoder.encode(filekey, "UTF-8")}"

    fun buildCdnDownloadUrl(encryptedQueryParam: String, cdnBaseUrl: String): String =
        "$cdnBaseUrl/download?encrypted_query_param=${
            java.net.URLEncoder.encode(encryptedQueryParam, "UTF-8")
        }"

    fun uploadBufferToCdn(
        plaintext: ByteArray, uploadParam: String, filekey: String,
        cdnBaseUrl: String, aesKey: ByteArray
    ): String? {
        val ciphertext = encryptAesEcb(plaintext, aesKey)
        val cdnUrl = buildCdnUploadUrl(cdnBaseUrl, uploadParam, filekey)

        var downloadParam: String? = null
        for (attempt in 1..UPLOAD_MAX_RETRIES) {
            try {
                val request = Request.Builder()
                    .url(cdnUrl)
                    .post(ciphertext.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .build()
                val response = httpClient.newCall(request).execute()
                val code = response.code
                val param = response.header("x-encrypted-param")
                response.close()
                if (code in 400..499) return null
                if (code != 200) continue
                if (param.isNullOrEmpty()) continue
                downloadParam = param
                break
            } catch (_: Exception) {
                if (attempt == UPLOAD_MAX_RETRIES) return null
            }
        }
        return downloadParam
    }

    fun parseAesKey(aesKeyBase64: String): ByteArray? {
        return try {
            val decoded = android.util.Base64.decode(aesKeyBase64, android.util.Base64.DEFAULT)
            when {
                decoded.size == 16 -> decoded
                decoded.size == 32 && String(decoded).matches(Regex("[0-9a-fA-F]{32}")) ->
                    hexToBytes(String(decoded))
                else -> null
            }
        } catch (_: Exception) { null }
    }

    fun downloadAndDecrypt(
        encryptedQueryParam: String, aesKeyBase64: String, cdnBaseUrl: String
    ): ByteArray? {
        val key = parseAesKey(aesKeyBase64) ?: return null
        val url = buildCdnDownloadUrl(encryptedQueryParam, cdnBaseUrl)
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            val encrypted = response.body?.bytes()
            response.close()
            encrypted?.let { decryptAesEcb(it, key) }
        } catch (_: Exception) { null }
    }

    fun md5Hex(data: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(data).toHexString()

    fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { SecureRandom().nextBytes(it) }.toHexString()

    fun generateId(prefix: String): String {
        val ts = System.currentTimeMillis()
        val hex = randomHex(4)
        return "$prefix:$ts-$hex"
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}