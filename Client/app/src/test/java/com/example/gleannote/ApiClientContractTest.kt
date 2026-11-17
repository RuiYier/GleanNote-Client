package com.example.gleannote

import com.example.gleannote.core.contract.AppResult
import com.example.gleannote.core.contract.AuthSession
import com.example.gleannote.core.network.OkHttpApiClient
import com.example.gleannote.core.network.SessionStore
import com.example.gleannote.core.network.get
import com.example.gleannote.core.network.post
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/*
 * 网络层契约用例（对应 GN-CLIENT-002 第 3、4、6 章与 C-01、C-06、C-13）。
 * 用 MockWebServer 起真实 HTTP，验证客户端对 Problem+JSON、幂等键、令牌刷新单飞、
 * 预签名直传的处理是否与服务端契约一致，不依赖 Android 运行时。
 */
class ApiClientContractTest {

    private lateinit var server: MockWebServer
    private lateinit var store: FakeSessionStore
    private lateinit var client: OkHttpApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = FakeSessionStore()
        client = OkHttpApiClient(server.url("/v1").toString(), store, OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /* 1. Problem+JSON：按 code 分支，变量只取 metadata（API.md 2.8 / 6 章）。 */
    @Test
    fun problemJsonParsedByCode() {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setHeader("Content-Type", "application/problem+json")
                .setBody(
                    """{"type":"about:blank","title":"Conflict","status":409,"detail":"同父级已存在同名目录",
                       "code":"NAME_DUPLICATED","userMessage":"名称已存在","metadata":{"folderId":"f-1"},
                       "retryable":false,"requestId":"req_abc"}"""
                )
        )

        val result = runBlocking { client.get<Map<String, Any>>("/folders") }
        assertTrue(result is AppResult.Failure)
        val error = (result as AppResult.Failure).error
        assertEquals("NAME_DUPLICATED", error.code)
        assertEquals(409, error.status)
        assertEquals("名称已存在", error.userMessage)
        assertEquals("f-1", error.metadata["folderId"])
        assertFalse(error.retryable)
        assertEquals("req_abc", error.requestId)
    }

    /* 2. 429 的 Retry-After 必须进 metadata，供 UI 展示倒计时（API.md 2.8）。 */
    @Test
    fun retryAfterHeaderGoesToMetadata() {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Content-Type", "application/problem+json")
                .setHeader("Retry-After", "30")
                .setBody("""{"code":"RATE_LIMITED","status":429,"userMessage":"操作过于频繁，请稍后重试","retryable":true}""")
        )

        val error = (runBlocking { client.post<Map<String, Any>>("/auth:sendCode", mapOf("email" to "a@b.com")) } as AppResult.Failure).error
        assertEquals("RATE_LIMITED", error.code)
        assertEquals("30", error.metadata["retryAfterSeconds"])
        assertTrue(error.retryable)
    }

    /* 3. 5xx 未声明 retryable 时按可重试处理，避免把服务端抖动判成永久失败。 */
    @Test
    fun serverErrorDefaultsToRetryable() {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("""{"code":"UNAVAILABLE","status":503,"userMessage":"服务暂时不可用，请稍后重试"}""")
        )

        val error = (runBlocking { client.get<Map<String, Any>>("/users/me") } as AppResult.Failure).error
        assertTrue(error.retryable)
        assertEquals("UNAVAILABLE", error.code)
    }

    /* 4. 未知 code 不得崩溃，回落到通用文案（服务端新增错误码时的向前兼容）。 */
    @Test
    fun unknownCodeFallsBackToGenericMessage() {
        server.enqueue(
            MockResponse().setResponseCode(418)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("""{"code":"BRAND_NEW_CODE","status":418}""")
        )

        val error = (runBlocking { client.get<Map<String, Any>>("/users/me") } as AppResult.Failure).error
        assertEquals("BRAND_NEW_CODE", error.code)
        assertEquals("请求失败，请重试", error.userMessage)
    }

    /* 5. 创建类请求必须带幂等键、设备号、令牌与请求 ID（API.md 2.3 / 2.6）。 */
    @Test
    fun createRequestCarriesContractHeaders() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"n-1"}"""))

        runBlocking {
            client.post<Map<String, Any>>("/notes", mapOf("title" to "t"), idempotencyKey = "key-12345678")
        }

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("key-12345678", recorded.getHeader("Idempotency-Key"))
        assertEquals("dev-test", recorded.getHeader("X-Device-Id"))
        assertEquals("Bearer access-1", recorded.getHeader("Authorization"))
        assertNotNull(recorded.getHeader("X-Request-Id"))
        assertNotNull(recorded.getHeader("X-Client-Version"))
    }

    /* 6. 并发 401 只允许刷新一次，其余请求复用同一结果（API.md 2.2 刷新单飞）。 */
    @Test
    fun concurrentUnauthorizedRefreshesOnce() {
        val refreshCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.contains("/auth:refresh")) {
                    refreshCount.incrementAndGet()
                    return MockResponse().setResponseCode(200).setBody(
                        """{"accessToken":"access-2","refreshToken":"refresh-2","expiresIn":900,"tokenType":"Bearer"}"""
                    )
                }
                return if (request.getHeader("Authorization") == "Bearer access-2") {
                    MockResponse().setResponseCode(200).setBody("""{"items":[],"totalSize":0}""")
                } else {
                    MockResponse().setResponseCode(401)
                        .setHeader("Content-Type", "application/problem+json")
                        .setBody("""{"code":"AUTH_TOKEN_EXPIRED","status":401,"userMessage":"登录已过期，请重新登录","retryable":false}""")
                }
            }
        }

        val results = runBlocking {
            listOf(
                async { client.get<Map<String, Any>>("/folders") },
                async { client.get<Map<String, Any>>("/folders") },
                async { client.get<Map<String, Any>>("/folders") }
            ).awaitAll()
        }

        assertEquals("并发 401 只应触发一次刷新", 1, refreshCount.get())
        assertTrue("刷新后应全部成功", results.all { it is AppResult.Success })
        assertEquals("access-2", store.access)
        assertEquals("refresh-2", store.refresh)
    }

    /* 7. 刷新失败必须清空会话，让 UI 回到登录页。 */
    @Test
    fun refreshFailureClearsSession() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path.orEmpty().contains("/auth:refresh")) {
                    MockResponse().setResponseCode(401)
                        .setHeader("Content-Type", "application/problem+json")
                        .setBody("""{"code":"AUTH_REFRESH_REUSED","status":401,"retryable":false}""")
                } else {
                    MockResponse().setResponseCode(401)
                        .setHeader("Content-Type", "application/problem+json")
                        .setBody("""{"code":"AUTH_TOKEN_EXPIRED","status":401,"retryable":false}""")
                }
            }
        }

        val result = runBlocking { client.get<Map<String, Any>>("/folders") }
        assertTrue(result is AppResult.Failure)
        assertNull("刷新失败后必须清空 accessToken", store.access)
    }

    /* 8. 预签名直传：保留给定 Content-Type，且不得带业务鉴权头（API.md 2.9）。 */
    @Test
    fun presignedPutKeepsContentTypeWithoutAuth() {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = runBlocking {
            client.putBinary(server.url("/bucket/p1.jpg").toString(), byteArrayOf(1, 2, 3), "image/jpeg")
        }

        val recorded = server.takeRequest()
        assertTrue(result is AppResult.Success)
        assertEquals("PUT", recorded.method)
        assertEquals("image/jpeg", recorded.getHeader("Content-Type"))
        assertNull(recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader("Idempotency-Key"))
    }

    /* 9. 204 空响应体按成功处理（退出登录、彻底删除等端点返回 204）。 */
    @Test
    fun emptyBodyIsSuccess() {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = runBlocking { client.post<Unit>("/auth:logout", mapOf("allDevices" to false)) }
        assertTrue(result is AppResult.Success)
    }

    /* 10. 连接失败映射为可重试的网络错误，不抛异常给上层。 */
    @Test
    fun networkFailureMapsToRetryableError() {
        server.shutdown()
        val result = runBlocking { client.get<Map<String, Any>>("/folders") }
        val error = (result as AppResult.Failure).error
        assertEquals("NETWORK_ERROR", error.code)
        assertTrue(error.retryable)
    }
}

/* 内存会话：替代 DataStore，供 JVM 用例注入。 */
private class FakeSessionStore(
    var access: String? = "access-1",
    var refresh: String? = "refresh-1"
) : SessionStore {

    override suspend fun accessToken(): String? = access

    override suspend fun refreshToken(): String? = refresh

    override suspend fun deviceId(): String = "dev-test"

    override suspend fun save(session: AuthSession) {
        access = session.accessToken
        refresh = session.refreshToken
    }

    override suspend fun clear() {
        access = null
        refresh = null
    }

    override val isLoggedIn: Flow<Boolean> = flowOf(true)
}
