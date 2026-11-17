package com.example.gleannote

import com.example.gleannote.core.markdown.MarkdownRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/* Markdown 分块与搜索结果片段处理（对应 FR-08、API.md 5.3.11）。 */
class MarkdownRendererTest {

    @Test
    fun parses_heading_and_paragraph_as_separate_blocks() {
        val blocks = MarkdownRenderer.parse("# 矩阵的秩\n\n矩阵的秩等于行阶梯形非零行数。")
        assertEquals(2, blocks.size)
        assertEquals("heading", blocks[0].type)
        assertEquals(1, blocks[0].level)
        assertEquals("paragraph", blocks[1].type)
    }

    @Test
    fun parses_code_block() {
        val blocks = MarkdownRenderer.parse("```\nval x = 1\n```")
        assertEquals(1, blocks.size)
        assertEquals("code", blocks[0].type)
    }

    @Test
    fun blank_input_returns_empty_list() {
        assertTrue(MarkdownRenderer.parse("   ").isEmpty())
    }

    @Test
    fun strips_em_tag_from_search_snippet() {
        assertEquals("矩阵的秩等于行阶梯形", MarkdownRenderer.plainText("矩阵的<em>秩</em>等于行阶梯形"))
    }
}
