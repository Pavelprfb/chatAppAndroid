package com.chatapp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.chatapp.api.ChatConversation
import com.chatapp.api.ChatMessage
import com.chatapp.api.ChatUser
import com.chatapp.security.RefreshTokenStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ChatCache(context: Context, private val secureStore: RefreshTokenStore) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    private val gson = Gson()

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE conversations (
                owner_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                payload TEXT NOT NULL,
                PRIMARY KEY(owner_id, conversation_id)
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE messages (
                owner_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                payload TEXT NOT NULL,
                PRIMARY KEY(owner_id, conversation_id, message_id)
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE search_results (
                owner_id TEXT NOT NULL,
                query TEXT NOT NULL,
                payload TEXT NOT NULL,
                PRIMARY KEY(owner_id, query)
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) return
        throw IllegalStateException("Unsupported chat cache migration: $oldVersion to $newVersion")
    }

    @Synchronized
    fun saveConversations(ownerId: String, conversations: List<ChatConversation>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("conversations", "owner_id = ?", arrayOf(ownerId))
            for (conversation in conversations) {
                insertEncrypted(
                    db,
                    "conversations",
                    ContentValues().apply {
                        put("owner_id", ownerId)
                        put("conversation_id", conversation.id)
                    },
                    gson.toJson(conversation)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun conversations(ownerId: String): List<ChatConversation> =
        readEncryptedRows(
            "conversations",
            "owner_id = ?",
            arrayOf(ownerId),
            ChatConversation::class.java
        ).sortedByDescending { it.updatedAt }

    @Synchronized
    fun saveMessages(ownerId: String, conversationId: String, messages: List<ChatMessage>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (message in messages) {
                insertEncrypted(
                    db,
                    "messages",
                    ContentValues().apply {
                        put("owner_id", ownerId)
                        put("conversation_id", conversationId)
                        put("message_id", message.id)
                    },
                    gson.toJson(message)
                )
            }
            db.execSQL(
                """DELETE FROM messages
                   WHERE owner_id = ? AND conversation_id = ?
                     AND rowid NOT IN (
                       SELECT rowid FROM messages
                       WHERE owner_id = ? AND conversation_id = ?
                       ORDER BY rowid DESC LIMIT 500
                     )""".trimIndent(),
                arrayOf(ownerId, conversationId, ownerId, conversationId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun messages(ownerId: String, conversationId: String): List<ChatMessage> =
        readEncryptedRows(
            "messages",
            "owner_id = ? AND conversation_id = ?",
            arrayOf(ownerId, conversationId),
            ChatMessage::class.java
        ).sortedBy { it.createdAt }

    @Synchronized
    fun saveSearch(ownerId: String, query: String, users: List<ChatUser>) {
        val normalized = query.trim().lowercase()
        val db = writableDatabase
        val values = ContentValues().apply {
            put("owner_id", ownerId)
            put("query", normalized)
        }
        insertEncrypted(db, "search_results", values, gson.toJson(users))
        db.execSQL(
            """DELETE FROM search_results
               WHERE owner_id = ? AND rowid NOT IN (
                 SELECT rowid FROM search_results WHERE owner_id = ?
                 ORDER BY rowid DESC LIMIT 25
               )""".trimIndent(),
            arrayOf(ownerId, ownerId)
        )
    }

    @Synchronized
    fun search(ownerId: String, query: String): List<ChatUser>? {
        val normalized = query.trim().lowercase()
        val cursor = readableDatabase.query(
            "search_results",
            arrayOf("payload"),
            "owner_id = ? AND query = ?",
            arrayOf(ownerId, normalized),
            null,
            null,
            null
        )
        return cursor.use {
            if (!it.moveToFirst()) return null
            val payload = secureStore.decryptValue(it.getString(0))
            val type = object : TypeToken<List<ChatUser>>() {}.type
            gson.fromJson<List<ChatUser>>(payload, type)
        }
    }

    @Synchronized
    fun clearOwner(ownerId: String) {
        writableDatabase.apply {
            delete("conversations", "owner_id = ?", arrayOf(ownerId))
            delete("messages", "owner_id = ?", arrayOf(ownerId))
            delete("search_results", "owner_id = ?", arrayOf(ownerId))
        }
    }

    private fun <T> readEncryptedRows(
        table: String,
        selection: String,
        arguments: Array<String>,
        type: Class<T>
    ): List<T> {
        val rows = mutableListOf<T>()
        val cursor = readableDatabase.query(table, arrayOf("payload"), selection, arguments, null, null, null)
        cursor.use {
            val payloadIndex = it.getColumnIndexOrThrow("payload")
            while (it.moveToNext()) {
                val payload = secureStore.decryptValue(it.getString(payloadIndex))
                rows.add(gson.fromJson(payload, type))
            }
        }
        return rows
    }

    private fun insertEncrypted(
        db: SQLiteDatabase,
        table: String,
        identifiers: ContentValues,
        payload: String
    ) {
        identifiers.put("payload", secureStore.encryptValue(payload))
        db.insertWithOnConflict(table, null, identifiers, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private companion object {
        const val DATABASE_NAME = "chat-cache.db"
        const val DATABASE_VERSION = 1
    }
}
