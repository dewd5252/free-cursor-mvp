package com.freecursor.app.core

import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap

object NodeRegistry {
    private val nodes = ConcurrentHashMap<Int, AccessibilityNodeInfo>()

    @Synchronized
    fun replace(newNodes: Map<Int, AccessibilityNodeInfo>) {
        nodes.values.forEach { node ->
            node.recycle()
        }
        nodes.clear()
        nodes.putAll(newNodes)
    }

    fun get(id: Int): AccessibilityNodeInfo? = nodes[id]

    @Synchronized
    fun clear() {
        nodes.values.forEach { node ->
            node.recycle()
        }
        nodes.clear()
    }
}
