package com.example.gleannote.core.network

import android.content.Context
import com.example.gleannote.BuildConfig
import com.example.gleannote.core.contract.ApiClient
import com.example.gleannote.core.contract.AppError
import com.example.gleannote.core.contract.AppResult
import com.example.gleannote.core.contract.AuthSession
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.lang.reflect.Type
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/*
 * OkHttp + Gson 实现。职责：统一请求头、Problem 解析、令牌刷新单飞、预签名直传。
 * 约束：按 code 分支不解析 detail；令牌/密码/验证码/完整邮箱禁止进日志（CONVENTIONS G-10 精神）。
 */
class OkHttpApiClient private constructor(
    private val baseUrl: String,
    private val tokenStore: SessionStore,
    private val client: OkHttpClient,
    private val scope: CoroutineScope
) : ApiClient {

    /* 生产入口：会话落 DataStore。 */
    constructor(context: Context, baseUrl: String) : this(
        baseUrl,
        TokenStore(context),
        defaultHttpClient(),
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    /* 测试入口：注入内存会话与 MockWebServer 的客户端，用于契约用例。 */
    constructor(baseUrl: String, store: SessionStore, httpClient: OkHttpClient) : this(
        baseUrl,
        store,
        httpClient,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private val gson = Gson()

    private val refreshMutex = Mutex()

    @Volatile
    private var refreshTask: Deferred<String?>? = null

    override val isLoggedIn: Flow<Boolean> = tokenStore.isLoggedIn

    override suspend fun saveSession(session: AuthSession) {
        tokenStore.save(session)
    }

    override fun invalidateSession() {
        scope.launch { tokenStore.clear() }
    }

    /* 主入口：401 过期时刷新并重放一次；其它错误原样返回。 */
    override suspend fun <T> request(
        method: String,
        path: String,
        body: Any?,
        query: Map<String, String?>,
        idempotencyKey: String?,
        responseType: Type,
        anonymous: Boolean,
        queryMulti: Map<String, List<String>>
    ): AppResult<T> {
        val first = execute<T>(method, path, body, query, queryMulti, idempotencyKey, responseType, anonymous)
        if (first is AppResult.Failure && first.error.code == "AUTH_TOKEN_EXPIRED" && !anonymous) {
            val token = refreshAccessToken()
            if (token != null) return execute<T>(method, path, body, query, queryMulti, idempotencyKey, responseType, anonymous)
        }
        return first
    }

    private suspend fun <T> execute(
        method: String,
        path: String,
        body: Any?,
        query: Map<String, String?>,
        queryMulti: Map<String, List<String>>,
        idempotencyKey: String?,
        responseType: Type,
        anonymous: Boolean
    ): AppResult<T> = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url(HttpUrls.build(baseUrl, path, query, queryMulti))
                .header("X-Client-Version", BuildConfig.CLIENT_VERSION)
                .header("X-Request-Id", "req_" + UUID.randomUUID().toString().replace("-", "").take(16))

            if (!anonymous) {
                tokenStore.accessToken()?.let { builder.header("Authorization", "Bearer $it") }
                builder.header("X-Device-Id", tokenStore.deviceId())
            }
            idempotencyKey?.let { builder.header("Idempotency-Key", it) }

            val requestBody = body?.let { gson.toJson(it).toRequestBody(JSON) }
            when (method.uppercase()) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete()
                "POST" -> builder.post(requestBody ?: EMPTY_BODY)
                "PATCH" -> builder.patch(requestBody ?: EMPTY_BODY)
                "PUT" -> builder.put(requestBody ?: EMPTY_BODY)
                else -> return@withContext AppResult.Failure(
                    AppError("METHOD_UNSUPPORTED", 0, "不支持的请求方法")
                )
            }

            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val problemBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    return@use AppResult.Failure(
                        ProblemMapper.parse(
                            status = response.code,
                            body = problemBody,
                            retryAfter = response.header("Retry-After"),
                            headerRequestId = response.header("X-Request-Id")
                        )
                    )
                }
                val text = response.body?.string().orEmpty()
                if (responseType == Unit::class.java || text.isBlank()) {
                    @Suppress("UNCHECKED_CAST")
                    val unit = Unit as T
                    return@use AppResult.Success(unit)
                }
                val data: T? = gson.fromJson(text, responseType)
                if (data == null) {
                    AppResult.Failure(AppError("PARSE_ERROR", response.code, "数据解析失败", retryable = true))
                } else {
                    AppResult.Success(data)
                }
            }
        } catch (e: IOException) {
            AppResult.Failure(AppError("NETWORK_ERROR", 0, "网络异常，请检查网络", retryable = true))
        } catch (e: Exception) {
            AppResult.Failure(AppError("INTERNAL", 0, "客户端处理失败", retryable = true))
        }
    }

    /* 预签名直传：不得修改调用方给定的 Content-Type（API.md 2.9）。 */
    override suspend fun putBinary(url: String, bytes: ByteArray, contentType: String): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .put(bytes.toRequestBody(contentType.toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) AppResult.Success(Unit)
                    else AppResult.Failure(
                        AppError("UPLOAD_FAILED", response.code, "图片上传失败，请重试", retryable = response.code >= 500)
                    )
                }
            } catch (e: IOException) {
                AppResult.Failure(AppError("NETWORK_ERROR", 0, "网络异常，请检查网络", retryable = true))
            }
        }

    /*
     * 刷新单飞：并发 401 只发一次 /auth:refresh，其余等待复用同一结果（API.md 2.2）。
     * 「检查 + 创建 + 登记」放在同一临界区，避免两个线程同时判定为空各发一次刷新。
     */
    private suspend fun refreshAccessToken(): String? {
        val task = refreshMutex.withLock {
            refreshTask ?: scope.async { doRefresh() }.also { refreshTask = it }
        }
        return try {
            task.await()
        } finally {
            refreshMutex.withLock { if (refreshTask === task) refreshTask = null }
        }
    }

    /* 实际刷新逻辑；失败一律清空会话，让 UI 回到登录页。 */
    private suspend fun doRefresh(): String? {
        val refreshToken = tokenStore.refreshToken() ?: return null
        val payload = mapOf("refreshToken" to refreshToken, "deviceId" to tokenStore.deviceId())
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/auth:refresh")
            .post(gson.toJson(payload).toRequestBody(JSON))
            .header("X-Client-Version", BuildConfig.CLIENT_VERSION)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    tokenStore.clear()
                    return@use null
                }
                val session = gson.fromJson(response.body?.string(), AuthSession::class.java)
                if (session == null || session.accessToken.isBlank()) {
                    tokenStore.clear()
                    return@use null
                }
                tokenStore.save(session)
                session.accessToken
            }
        } catch (e: IOException) {
            null
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = "".toRequestBody(JSON)
    }
}

/* 通用 reified 扩展：Repository 通过它们调用端点，避免 58 个方法各写一遍样板。 */
suspend inline fun <reified T> ApiClient.get(
    path: String,
    query: Map<String, String?> = emptyMap(),
    anonymous: Boolean = false,
    queryMulti: Map<String, List<String>> = emptyMap()
): AppResult<T> = request("GET", path, null, query, null, object : TypeToken<T>() {}.type, anonymous, queryMulti)

suspend inline fun <reified T> ApiClient.post(
    path: String,
    body: Any? = null,
    idempotencyKey: String? = null,
    anonymous: Boolean = false
): AppResult<T> = request("POST", path, body, emptyMap(), idempotencyKey, object : TypeToken<T>() {}.type, anonymous)

suspend inline fun <reified T> ApiClient.patch(
    path: String,
    body: Any? = null,
    idempotencyKey: String? = null
): AppResult<T> = request("PATCH", path, body, emptyMap(), idempotencyKey, object : TypeToken<T>() {}.type)

suspend inline fun <reified T> ApiClient.put(
    path: String,
    body: Any? = null,
    idempotencyKey: String? = null
): AppResult<T> = request("PUT", path, body, emptyMap(), idempotencyKey, object : TypeToken<T>() {}.type)

suspend inline fun <reified T> ApiClient.delete(
    path: String,
    query: Map<String, String?> = emptyMap(),
    body: Any? = null
): AppResult<T> = request("DELETE", path, body, query, null, object : TypeToken<T>() {}.type)

/* 幂等键：8–64 位可见 ASCII，24 小时内唯一（API.md 2.6）。 */
fun newIdempotencyKey(): String = UUID.randomUUID().toString().replace("-", "")

/* 默认超时口径：连接 15s、读写 60s（主文档 8.2）。 */
private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()
