package com.example.gleannote

import com.example.gleannote.core.markdown.MarkdownRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/*
 * 表格解析：GFM 表格应被识别为独立 Block，并保留表头、单元格与列对齐。
 * 对应预览渲染的 table 分支，纯 JVM 可跑。
 */
class MarkdownTableTest {

    private val source = """
        | 项目 | 状态 | 备注 |
        |:-----|:----:|-----:|
        | 上传 | 完成 | 3 张 |
        | 转换 | 进行中 | 第 2 页 |
    """.trimIndent()

    @Test
    fun parses_gfm_table_into_structured_block() {
        val blocks = MarkdownRenderer.parse(source)
        assertEquals(1, blocks.size)

        val block = blocks[0]
        assertEquals("table", block.type)
        val table = block.table
        assertNotNull(table)
        table!!

        assertEquals(listOf("项目", "状态", "备注"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("上传", "完成", "3 张"), table.rows[0])
        assertEquals(listOf("转换", "进行中", "第 2 页"), table.rows[1])
        /* 分隔行分别是 :--、:-:、--:，对应左、中、右 */
        assertEquals(listOf(0, 1, 2), table.aligns)
    }

    @Test
    fun table_keeps_position_among_other_blocks() {
        val blocks = MarkdownRenderer.parse("# 标题\n\n$source\n\n结尾段落")
        assertEquals(listOf("heading", "table", "paragraph"), blocks.map { it.type })
    }

    @Test
    fun plain_pipe_text_without_separator_is_not_a_table() {
        val blocks = MarkdownRenderer.parse("这里只是用 | 分隔的一段普通文字。")
        assertEquals(listOf("paragraph"), blocks.map { it.type })
    }
}
