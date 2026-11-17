package com.example.gleannote.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/*
 * 时间展示口径（PRD 5.4 / 主文档 6.11）：
 * 服务端一律返回 RFC3339 UTC，客户端只存 UTC，展示时统一转北京时间。
 * 任何界面都不得直接展示 ISO 原文。
 */
object TimeFormat {

    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    /* 解析失败时返回空串，由调用方决定回退文案，避免把异常字符串展示给用户。 */
    private fun parse(iso: String): Instant? = runCatching { Instant.parse(iso) }.getOrNull()

    /** 完整日期时间：2026-11-15 20:54 */
    fun dateTime(iso: String): String =
        parse(iso)?.atZone(ZONE)?.format(DATE_TIME) ?: ""

    /** 仅日期：2026-11-15 */
    fun date(iso: String): String =
        parse(iso)?.atZone(ZONE)?.format(DATE) ?: ""

    /**
     * 相对时间（列表与版本历史用）：
     * 1 分钟内「刚刚」、1 小时内「N 分钟前」、当天「HH:mm」、昨天「昨天 HH:mm」、更早显示日期。
     */
    fun relative(iso: String): String {
        val instant = parse(iso) ?: return ""
        val zoned = instant.atZone(ZONE)
        val now = Instant.now().atZone(ZONE)
        val minutes = ChronoUnit.MINUTES.between(zoned, now)
        val days = ChronoUnit.DAYS.between(zoned.toLocalDate(), now.toLocalDate())
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            days == 0L -> zoned.format(TIME)
            days == 1L -> "昨天 ${zoned.format(TIME)}"
            else -> zoned.format(DATE)
        }
    }

    /** 回收站剩余天数：按北京时间自然日计算，至少 0。 */
    fun daysUntil(iso: String): Long? {
        val target = parse(iso)?.atZone(ZONE)?.toLocalDate() ?: return null
        return ChronoUnit.DAYS.between(LocalDate.now(ZONE), target).coerceAtLeast(0)
    }
}
