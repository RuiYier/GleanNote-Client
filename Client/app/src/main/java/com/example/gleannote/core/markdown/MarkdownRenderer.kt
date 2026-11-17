package com.example.gleannote.core.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser

/*
 * Markdown 解析与轻量高亮。编辑态高亮只处理可视区由 UI 控制，本层保持无状态。
 * 预览按 Block 渐进渲染，避免 20 万字符一次性布局（PRD 5.2、主文档 4.2 / R-C01）。
 */
object MarkdownRenderer {

    /*
     * 表格单独用结构化字段承载，而不是压成一段文本：
     * 预览需要按列对齐渲染，压平后就无法还原列边界与对齐方式。
     */
    data class TableData(
        val headers: List<String>,
        val rows: List<List<String>>,
        /* 每列对齐：0 左对齐、1 居中、2 右对齐，来自 GFM 分隔行的冒号 */
        val aligns: List<Int>
    )

    data class Block(
        val type: String,
        val text: String,
        val level: Int = 0,
        val table: TableData? = null
    )

    private val extensions = listOf(TablesExtension.create())

    private val parser = Parser.builder().extensions(extensions).build()

    fun parse(markdown: String): List<Block> {
        if (markdown.isBlank()) return emptyList()
        val document = parser.parse(markdown)
        val blocks = mutableListOf<Block>()
        var child = document.firstChild
        while (child != null) {
            blocks += child.toBlock()
            child = child.next
        }
        return blocks
    }

    private fun Node.toBlock(): Block = when (this) {
        is Heading -> Block("heading", textOf(this), level)
        is BlockQuote -> Block("quote", textOf(this))
        is FencedCodeBlock -> Block("code", literal)
        is IndentedCodeBlock -> Block("code", literal)
        is BulletList -> Block("list", textOf(this))
        is OrderedList -> Block("list", textOf(this))
        is ThematicBreak -> Block("other", "———")
        is TableBlock -> Block("table", "", table = toTable(this))
        is Paragraph -> {
            val text = textOf(this)
            if (text.startsWith("[ ]") || text.startsWith("[x]")) Block("todo", text) else Block("paragraph", text)
        }
        else -> Block("other", textOf(this))
    }

    /* 表格节点转结构化数据：表头取 TableHead 下的首个 TableRow，正文取 TableBody 的每行。 */
    private fun toTable(block: TableBlock): TableData {
        var head: TableHead? = null
        var body: TableBody? = null
        var child = block.firstChild
        while (child != null) {
            when (child) {
                is TableHead -> head = child
                is TableBody -> body = child
            }
            child = child.next
        }

        fun cellsOf(row: TableRow): List<TableCell> {
            val cells = mutableListOf<TableCell>()
            var c = row.firstChild
            while (c != null) {
                if (c is TableCell) cells += c
                c = c.next
            }
            return cells
        }

        val headRow = head?.firstChild as? TableRow
        val headerCells = headRow?.let { cellsOf(it) } ?: emptyList()
        val headers = headerCells.map { textOf(it) }
        val aligns = headerCells.map {
            when (it.alignment) {
                TableCell.Alignment.CENTER -> 1
                TableCell.Alignment.RIGHT -> 2
                else -> 0
            }
        }

        val rows = mutableListOf<List<String>>()
        var rowNode = body?.firstChild
        while (rowNode != null) {
            if (rowNode is TableRow) rows += cellsOf(rowNode).map { textOf(it) }
            rowNode = rowNode.next
        }
        return TableData(headers, rows, aligns)
    }

    private fun textOf(node: Node): String {
        val builder = StringBuilder()
        fun walk(n: Node) {
            var child = n.firstChild
            while (child != null) {
                when (child) {
                    is Text -> builder.append(child.literal)
                    is Code -> builder.append('`').append(child.literal).append('`')
                    is SoftLineBreak, is HardLineBreak -> builder.append('\n')
                    is ListItem -> {
                        builder.append("· ")
                        walk(child)
                        builder.append('\n')
                    }
                    is Link -> {
                        builder.append("[")
                        walk(child)
                        builder.append("](").append(child.destination).append(")")
                    }
                    else -> walk(child)
                }
                child = child.next
            }
        }
        walk(node)
        return builder.toString().trim()
    }

    /*
     * 编辑态轻量语法高亮：只覆盖标题、加粗、斜体、行内代码、列表、引用。
     * 颜色以参数注入（默认值保持原有观感），调用方应传 MaterialTheme 语义色，
     * 这样深色主题下行内代码底色与引用文字不会因硬编码浅色而失去对比度。
     */
    fun highlight(
        source: String,
        headingColor: Color = Color(0xFF185FA5),
        codeBackground: Color = Color(0xFFEEF0F3),
        quoteColor: Color = Color(0xFF5F5E5A)
    ): AnnotatedString = buildAnnotatedString {
        append(source)
        val heading = Regex("(?m)^#{1,6}\\s.*$")
        heading.findAll(source).forEach {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = headingColor), it.range.first, it.range.last + 1)
        }
        val bold = Regex("\\*\\*(.+?)\\*\\*")
        bold.findAll(source).forEach {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), it.range.first, it.range.last + 1)
        }
        val italic = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")
        italic.findAll(source).forEach {
            addStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), it.range.first, it.range.last + 1)
        }
        val inlineCode = Regex("`([^`]+)`")
        inlineCode.findAll(source).forEach {
            addStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
                it.range.first, it.range.last + 1
            )
        }
        val quote = Regex("(?m)^>.*$")
        quote.findAll(source).forEach {
            addStyle(SpanStyle(color = quoteColor), it.range.first, it.range.last + 1)
        }
    }

    /* 搜索结果 snippet 只允许 <em> 一种标记，渲染前剥离（API.md 5.3.11）。 */
    fun plainText(snippet: String): String = snippet.replace(Regex("</?em>"), "")
}
