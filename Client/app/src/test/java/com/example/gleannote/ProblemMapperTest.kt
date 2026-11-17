package com.example.gleannote

import com.example.gleannote.core.network.ProblemMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* 契约测试：Problem+JSON 解析（API.md 2.8 / 6 章），对应 C-11 未知错误码容忍。 */
class ProblemMapperTest {

    private val quotaBody = """
        {"type":"https://gleannote.example/problems/quota-exceeded","title":"Daily quota exceeded",
         "status":409,"detail":"Requested 12 pages but only 5 remain.","code":"QUOTA_EXCEEDED",
         "userMessage":"今日额度剩余 5 张，可减少图片后重试",
         "metadata":{"remaining":5,"requested":12,"resetAt":"2026-10-28T16:00:00Z"},
         "retryable":false,"requestId":"req_01JB7Q2K9S"}
    """.trimIndent()

    @Test
    fun parses_code_status_and_metadata() {
        val error = ProblemMapper.parse(409, quotaBody)
        assertEquals("QUOTA_EXCEEDED", error.code)
        assertEquals(409, error.status)
        assertEquals("5", error.metadata["remaining"])
        assertEquals("12", error.metadata["requested"])
        assertEquals("req_01JB7Q2K9S", error.requestId)
        assertFalse(error.retryable)
    }

    @Test
    fun retry_after_header_is_exposed_in_metadata() {
        val error = ProblemMapper.parse(429, """{"code":"RATE_LIMITED"}""", retryAfter = "30")
        assertEquals("30", error.metadata["retryAfterSeconds"])
    }

    @Test
    fun server_error_defaults_to_retryable() {
        val error = ProblemMapper.parse(503, """{"code":"UNAVAILABLE"}""")
        assertTrue(error.retryable)
    }

    @Test
    fun non_json_body_falls_back_to_http_code() {
        val error = ProblemMapper.parse(502, "<html>bad gateway</html>", headerRequestId = "req_hdr")
        assertEquals("HTTP_502", error.code)
        assertEquals("req_hdr", error.requestId)
        assertTrue(error.retryable)
    }

    @Test
    fun unknown_code_gets_generic_message() {
        val error = ProblemMapper.parse(418, """{"code":"TEAPOT_UNKNOWN"}""")
        assertEquals("TEAPOT_UNKNOWN", error.code)
        assertEquals("请求失败，请重试", error.userMessage)
    }

    @Test
    fun known_code_without_user_message_uses_catalog() {
        val error = ProblemMapper.parse(409, """{"code":"STORAGE_EXCEEDED"}""")
        assertEquals("云端空间不足，请清理后重试", error.userMessage)
    }
}
