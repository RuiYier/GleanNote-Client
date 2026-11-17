package com.example.gleannote.core.network

import java.net.URLEncoder

/*
 * URL 构造：单值参数忽略 null，多值参数用重复键（API.md 2.5 语义为「同时包含」）。
 * 抽成纯函数便于单测，避免分页/筛选参数拼接错误。
 */
object HttpUrls {

    fun build(
        baseUrl: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
        queryMulti: Map<String, List<String>> = emptyMap()
    ): String {
        val base = baseUrl.trimEnd('/') + path
        val pairs = mutableListOf<String>()
        query.filterValues { it != null }
            .forEach { (key, value) -> pairs += encode(key) + "=" + encode(value!!) }
        queryMulti.forEach { (key, values) ->
            values.forEach { value -> pairs += encode(key) + "=" + encode(value) }
        }
        return if (pairs.isEmpty()) base else base + "?" + pairs.joinToString("&")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
