package com.example.gleannote

import com.example.gleannote.core.network.HttpUrls
import org.junit.Assert.assertEquals
import org.junit.Test

/* 契约测试：URL 与查询参数拼接（API.md 2.4 / 2.5），对应 C-10 分页与筛选参数。 */
class HttpUrlsTest {

    private val base = "http://10.0.2.2:8080/v1"

    @Test
    fun no_query_returns_plain_url() {
        assertEquals("$base/notes", HttpUrls.build(base, "/notes"))
    }

    @Test
    fun null_values_are_omitted() {
        val url = HttpUrls.build(
            base, "/notes",
            query = mapOf("folderId" to "f1", "pageToken" to null, "pageSize" to "20")
        )
        assertEquals("$base/notes?folderId=f1&pageSize=20", url)
    }

    @Test
    fun multi_values_use_repeated_keys() {
        val url = HttpUrls.build(
            base, "/notes:search",
            query = mapOf("query" to "矩阵"),
            queryMulti = mapOf("tagIds" to listOf("t1", "t2"))
        )
        assertEquals("$base/notes:search?query=%E7%9F%A9%E9%98%B5&tagIds=t1&tagIds=t2", url)
    }

    @Test
    fun trailing_slash_in_base_is_normalized() {
        assertEquals("$base/folders", HttpUrls.build("$base/", "/folders"))
    }
}
