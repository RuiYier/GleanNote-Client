package com.example.gleannote.core.network

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.gleannote.core.contract.AuthSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/*
 * 会话读写抽象：网络层只依赖该接口，便于在 JVM 上用内存实现做契约测试，
 * 生产实现为下方的 DataStore 版本（主文档 8.2）。
 */
interface SessionStore {
    suspend fun accessToken(): String?
    suspend fun refreshToken(): String?
    suspend fun deviceId(): String
    suspend fun save(session: AuthSession)
    suspend fun clear()
    val isLoggedIn: Flow<Boolean>
}

/* 会话持久化。课程联调阶段用 DataStore 明文存储；生产环境必须改为 Keystore 加密（主文档 8.2）。 */
private val Context.sessionStore by preferencesDataStore(name = "gleannote_session")

class TokenStore(private val context: Context) : SessionStore {

    private object Keys {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        val DEVICE = stringPreferencesKey("device_id")
    }

    override suspend fun accessToken(): String? = context.sessionStore.data.first()[Keys.ACCESS]

    override suspend fun refreshToken(): String? = context.sessionStore.data.first()[Keys.REFRESH]

    /* 设备标识用于刷新令牌绑定与登录态吊销；卸载重装视为新设备（Q-08 临时口径）。 */
    override suspend fun deviceId(): String {
        val existing = context.sessionStore.data.first()[Keys.DEVICE]
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        context.sessionStore.edit { it[Keys.DEVICE] = generated }
        return generated
    }

    override suspend fun save(session: AuthSession) {
        context.sessionStore.edit { prefs ->
            prefs[Keys.ACCESS] = session.accessToken
            prefs[Keys.REFRESH] = session.refreshToken
        }
    }

    override suspend fun clear() {
        context.sessionStore.edit { prefs ->
            prefs.remove(Keys.ACCESS)
            prefs.remove(Keys.REFRESH)
        }
    }

    override val isLoggedIn: Flow<Boolean> =
        context.sessionStore.data.map { !it[Keys.REFRESH].isNullOrBlank() }
}
