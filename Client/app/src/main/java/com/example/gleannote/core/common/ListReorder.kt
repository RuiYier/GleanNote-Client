package com.example.gleannote.core.common

/*
 * 列表重排：拖拽排序的纯逻辑，与 UI 解耦以便单测。
 * 越界或同位置时原样返回，避免拖拽手势在边缘抖动时破坏列表（PRD FR-03 排序）。
 */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to) return this
    if (from !in indices || to !in indices) return this
    val mutable = toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return mutable
}
