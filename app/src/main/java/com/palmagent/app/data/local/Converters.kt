package com.palmagent.app.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.palmagent.app.model.Question

/**
 * Room TypeConverter：List<Question> ↔ JSON 字符串。
 * 问题卡数据内联存储在消息表中，避免额外关联表。
 */
class Converters {

    private val gson = Gson()

    @TypeConverter
    fun fromQuestionList(value: List<Question>?): String? {
        if (value.isNullOrEmpty()) return null
        return try {
            gson.toJson(value)
        } catch (e: Exception) {
            null
        }
    }

    @TypeConverter
    fun toQuestionList(value: String?): List<Question>? {
        if (value.isNullOrBlank()) return null
        return try {
            val type = object : TypeToken<List<Question>>() {}.type
            gson.fromJson<List<Question>>(value, type)
        } catch (e: Exception) {
            null
        }
    }
}
