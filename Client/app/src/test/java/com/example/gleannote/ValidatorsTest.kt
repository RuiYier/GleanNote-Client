package com.example.gleannote

import com.example.gleannote.core.common.Validators
import com.example.gleannote.core.contract.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* 端上校验的边界用例（对应 C-12）。规则出处见 Validators 注释。 */
class ValidatorsTest {

    @Test
    fun email_accepts_normal_address() {
        assertTrue(Validators.isValidEmail("student@cczu.edu.cn"))
    }

    @Test
    fun email_rejects_missing_domain_suffix() {
        assertFalse(Validators.isValidEmail("student@cczu"))
    }

    @Test
    fun password_requires_letter_and_digit() {
        assertTrue(Validators.isValidPassword("abc12345"))
        assertFalse(Validators.isValidPassword("abcdefgh"))
        assertFalse(Validators.isValidPassword("12345678"))
        assertFalse(Validators.isValidPassword("a1"))
    }

    @Test
    fun folder_name_rejects_forbidden_characters() {
        assertTrue(Validators.isValidFolderName("线性代数"))
        assertFalse(Validators.isValidFolderName("数学/代数"))
        assertFalse(Validators.isValidFolderName(" 前后空格 "))
        assertFalse(Validators.isValidFolderName(""))
        assertFalse(Validators.isValidFolderName("a".repeat(Limits.MAX_FOLDER_NAME + 1)))
    }

    @Test
    fun char_count_treats_chinese_as_single_character() {
        assertEquals(3, Validators.charCount("中文a"))
    }

    @Test
    fun char_limit_is_inclusive() {
        assertTrue(Validators.withinCharLimit("a".repeat(Limits.MAX_CHARS)))
        assertFalse(Validators.withinCharLimit("a".repeat(Limits.MAX_CHARS + 1)))
    }

    @Test
    fun page_limit_blocks_exceeding_fifty() {
        assertTrue(Validators.canAddPages(48, 2))
        assertFalse(Validators.canAddPages(49, 2))
        assertFalse(Validators.canAddPages(0, Limits.MAX_IMAGES_PER_BATCH + 1))
    }

    @Test
    fun quota_and_storage_boundaries() {
        assertTrue(Validators.quotaEnough(5, 5))
        assertFalse(Validators.quotaEnough(4, 5))
        assertTrue(Validators.storageEnough(Limits.STORAGE_LIMIT - 10, 10))
        assertFalse(Validators.storageEnough(Limits.STORAGE_LIMIT - 9, 10))
    }
}
