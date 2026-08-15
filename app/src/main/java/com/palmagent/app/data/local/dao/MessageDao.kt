package com.palmagent.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.palmagent.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    /** 按时间正序观察某会话的全部消息（供当前会话聊天界面刷新） */
    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: String): List<MessageEntity>

    @Query("DELETE FROM chat_message WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("UPDATE chat_message SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}
