package com.example.gleannote.core.common

import com.example.gleannote.core.contract.Limits

/*
 * 端上即时校验：只为减少无效请求，最终以服务端裁决为准（主文档 8.3）。
 * 规则出处：PRD FR-01 / FR-02 / 5.1 / 5.2，API.md 5.1.2 / 5.2.3。
 */
object Validators {

    private val EMAIL = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val FORBIDDEN_FOLDER_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun isValidEmail(email: String): Boolean = email.length <= 254 && EMAIL.matches(email)

    fun isValidPassword(password: String): Boolean =
        password.length in 8..32 && password.any { it.isLetter() } && password.any { it.isDigit() }

    fun isValidFolderName(name: String): Boolean =
        name.length in 1..Limits.MAX_FOLDER_NAME &&
            name == name.trim() &&
            name.none { it in FORBIDDEN_FOLDER_CHARS }

    fun isValidTagName(name: String): Boolean = name.length in 1..20 && name == name.trim()

    fun isValidTitle(title: String): Boolean = title.length <= Limits.MAX_TITLE

    /* 按 Unicode 字符计数，中文/英文/符号各计 1（API.md 2.1）。 */
    fun charCount(text: String): Int = text.codePointCount(0, text.length)

    fun withinCharLimit(text: String, limit: Int = Limits.MAX_CHARS): Boolean = charCount(text) <= limit

    fun canAddPages(current: Int, adding: Int): Boolean =
        adding in 1..Limits.MAX_IMAGES_PER_BATCH && current + adding <= Limits.MAX_PAGES_PER_NOTE

    fun quotaEnough(remaining: Int, requested: Int): Boolean = remaining >= requested

    fun storageEnough(usedBytes: Long, addingBytes: Long, limitBytes: Long = Limits.STORAGE_LIMIT): Boolean =
        usedBytes + addingBytes <= limitBytes
}
