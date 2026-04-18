package com.freecursor.app.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class NodeExtractor {
    data class ExtractionResult(
        val nodes: List<ScreenNodeDto>,
        val nodeMap: Map<Int, AccessibilityNodeInfo>,
    )

    fun extract(root: AccessibilityNodeInfo?): ExtractionResult {
        if (root == null) {
            return ExtractionResult(emptyList(), emptyMap())
        }

        val candidates = mutableListOf<NodeCandidate>()
        traverse(root, candidates)

        val sorted = candidates
            .sortedWith(compareBy<NodeCandidate> { it.bounds.t }.thenBy { it.bounds.l }.thenBy { it.order })

        val nodeMap = linkedMapOf<Int, AccessibilityNodeInfo>()
        val nodes = sorted.mapIndexed { index, candidate ->
            val id = index + 1
            nodeMap[id] = AccessibilityNodeInfo.obtain(candidate.node)

            ScreenNodeDto(
                id = id,
                role = candidate.role,
                text = candidate.text,
                hint = candidate.hint,
                clickable = candidate.clickable,
                enabled = candidate.enabled,
                editable = candidate.editable,
                bounds = candidate.bounds,
                packageName = candidate.packageName,
                className = candidate.className,
                actions = candidate.actions,
            )
        }

        return ExtractionResult(nodes, nodeMap)
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        candidates: MutableList<NodeCandidate>,
    ) {
        if (isRelevant(node)) {
            candidates.add(toCandidate(node, candidates.size))
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            traverse(child, candidates)
            child.recycle()
        }
    }

    private fun isRelevant(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) {
            return false
        }

        val hasUsefulText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        val interactive = node.isClickable || node.isCheckable || node.isEditable

        return hasUsefulText || interactive
    }

    private fun toCandidate(node: AccessibilityNodeInfo, order: Int): NodeCandidate {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        return NodeCandidate(
            node = node,
            order = order,
            role = inferRole(node),
            text = normalizeText(node.text ?: node.contentDescription),
            hint = normalizeText(node.hintText),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            editable = node.isEditable,
            bounds = BoundsDto(rect.left, rect.top, rect.right, rect.bottom),
            packageName = node.packageName?.toString() ?: "",
            className = node.className?.toString() ?: "",
            actions = extractActions(node),
        )
    }

    private fun normalizeText(raw: CharSequence?): String {
        if (raw == null) {
            return ""
        }
        return raw.toString().trim()
    }

    private fun inferRole(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString()?.lowercase(Locale.US) ?: return "view"

        return when {
            cls.contains("button") -> "button"
            cls.contains("edittext") || node.isEditable -> "textfield"
            cls.contains("checkbox") -> "checkbox"
            cls.contains("switch") -> "switch"
            cls.contains("image") -> "image"
            cls.contains("text") -> "text"
            cls.contains("recycler") || cls.contains("list") -> "list"
            else -> "view"
        }
    }

    private fun extractActions(node: AccessibilityNodeInfo): List<String> {
        return node.actionList.mapNotNull { action ->
            when (action.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> "click"
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_press"
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
                AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
                else -> null
            }
        }.distinct()
    }

    private data class NodeCandidate(
        val node: AccessibilityNodeInfo,
        val order: Int,
        val role: String,
        val text: String,
        val hint: String,
        val clickable: Boolean,
        val enabled: Boolean,
        val editable: Boolean,
        val bounds: BoundsDto,
        val packageName: String,
        val className: String,
        val actions: List<String>,
    )
}
