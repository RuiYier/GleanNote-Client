package com.example.gleannote.core.contract

/*
 * 取图与批量导入的目标目录。
 *
 * 由笔记库在跳转前写入当前目录，取图页/导入页读取后使用；
 * 这样避免为两个页面各加一个路由参数，同时保证「默认取当前所在目录」的口径（PRD FR-03、FR-17）。
 */
object NavTarget {
    var folderId: String? = null

    fun set(id: String?) { folderId = id }

    fun take(): String? = folderId
}
