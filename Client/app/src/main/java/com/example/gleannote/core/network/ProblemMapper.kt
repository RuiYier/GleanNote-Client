package com.example.gleannote.core.network

import com.example.gleannote.core.contract.AppError
import com.google.gson.JsonParser

/*
 * Problem+JSON 解析（API.md 2.8 / 6 章）。抽成纯函数便于单测，不依赖 OkHttp 与 Android。
 * 约束：按 code 分支，业务变量只从 metadata 读取，不解析 detail / userMessage 内容。
 */
object ProblemMapper {

    fun parse(
        status: Int,
        body: String,
        retryAfter: String? = null,
        headerRequestId: String? = null
    ): AppError {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        val code = obj?.get("code")?.takeIf { !it.isJsonNull }?.asString ?: "HTTP_$status"
        val userMessage = obj?.get("userMessage")?.takeIf { !it.isJsonNull }?.asString
            ?.takeIf { it.isNotBlank() } ?: defaultMessage(code)

        val metadata = mutableMapOf<String, String>()
        obj?.getAsJsonObject("metadata")?.entrySet()?.forEach { (key, value) ->
            metadata[key] = if (value.isJsonPrimitive) value.asString else value.toString()
        }
        if (!retryAfter.isNullOrBlank()) metadata["retryAfterSeconds"] = retryAfter

        val retryable = obj?.get("retryable")?.takeIf { !it.isJsonNull }?.asBoolean ?: (status >= 500)
        val requestId = obj?.get("requestId")?.takeIf { !it.isJsonNull }?.asString
            ?: headerRequestId.orEmpty()

        return AppError(code, status, userMessage, metadata, retryable, requestId)
    }

    fun defaultMessage(code: String): String = when (code) {
        "AUTH_INVALID_CREDENTIALS" -> "邮箱或密码不正确"
        "AUTH_EMAIL_ALREADY_EXISTS" -> "该邮箱已注册，请直接登录"
        "AUTH_CODE_INVALID" -> "验证码不正确，请重新输入"
        "AUTH_CODE_EXPIRED" -> "验证码已过期，请重新获取"
        "AUTH_TOKEN_EXPIRED", "AUTH_TOKEN_INVALID", "AUTH_REFRESH_REUSED" -> "登录已过期，请重新登录"
        "AUTH_AGREEMENT_REQUIRED" -> "请先阅读并同意用户协议与隐私政策"
        "QUOTA_EXCEEDED" -> "今日额度不足"
        "STORAGE_EXCEEDED" -> "云端空间不足，请清理后重试"
        "NAME_DUPLICATED" -> "名称已存在"
        "FOLDER_DEPTH_EXCEEDED" -> "最多支持 5 层目录"
        "FOLDER_CYCLE" -> "不能移动到自身或其子目录"
        "NOTE_TOO_LARGE" -> "正文已达上限"
        "NOTE_PAGE_LIMIT_EXCEEDED" -> "单篇笔记最多 50 张图片"
        "TAG_LIMIT_EXCEEDED" -> "每篇笔记最多 10 个标签"
        "UPLOAD_EXPIRED" -> "上传已超时，请重新上传"
        "UPLOAD_NOT_READY" -> "图片尚未上传完成"
        "CONVERSION_FAILED" -> "转换失败，请重试"
        "PROVIDER_UNAVAILABLE" -> "当前使用人数较多，请稍后重试"
        "IMAGE_UNREADABLE" -> "部分页面不清晰，建议重拍后重试"
        "CONTENT_UNSUPPORTED" -> "该内容暂不支持转换"
        "CONVERSION_TIMEOUT" -> "转换超时，请重试"
        "EMPTY_RESULT" -> "未能识别到可用的文字内容"
        "NOTE_MODIFIED" -> "笔记在转换期间被修改，请重试"
        "RESOURCE_NOT_FOUND" -> "内容不存在或已被删除"
        "SYNC_CURSOR_EXPIRED" -> "数据已更新，请重新同步"
        "INVALID_PAGE_TOKEN" -> "列表已变化，请刷新后重试"
        "IDEMPOTENCY_KEY_REUSED" -> "操作冲突，请刷新后重试"
        "RATE_LIMITED" -> "操作过于频繁，请稍后重试"
        "UNAVAILABLE" -> "服务暂时不可用，请稍后重试"
        "TIMEOUT" -> "请求超时，请重试"
        "INTERNAL" -> "服务异常，请稍后重试"
        else -> "请求失败，请重试"
    }
}
