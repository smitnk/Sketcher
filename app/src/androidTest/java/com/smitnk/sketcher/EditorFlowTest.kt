package com.smitnk.sketcher

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import org.junit.Rule
import org.junit.Test

private const val TIMEOUT = 180_000L

class EditorFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun awaitGeneration() {
        // The overlay may be too brief to observe on a fast run; tolerate that, then wait for it to go.
        try {
            rule.waitUntil(15_000) { overlayShown() }
        } catch (_: Throwable) {
        }
        rule.waitUntil(TIMEOUT) { !overlayShown() }
    }

    private fun overlayShown() =
        rule.onAllNodesWithTag("generating_overlay").fetchSemanticsNodes().isNotEmpty()

    private fun openEditorWithDemo() {
        rule.waitUntil(TIMEOUT) { has("Try demo") }
        rule.onNodeWithText("Try demo").performClick()
        awaitGeneration()
        // Wait for the editor chrome and skip the draw-along so the tool dock is visible.
        rule.waitUntil(TIMEOUT) { hasDesc("Show finished") }
        rule.onNodeWithContentDescription("Show finished").performClick()
        rule.waitUntil(TIMEOUT) { hasDesc("Undo") }
    }

    private fun has(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun hasDesc(desc: String, substring: Boolean = false) =
        rule.onAllNodesWithContentDescription(desc, substring = substring).fetchSemanticsNodes().isNotEmpty()

    // TopBar actions are a clickable Column (merged) with a text label; the icon has no contentDescription.
    private fun clickTopAction(label: String) {
        rule.waitUntil(TIMEOUT) { has(label) }
        rule.onAllNodesWithText(label).onFirst().performClick()
    }

    private fun openLayers() {
        clickTopAction("Layers")
        rule.waitUntil(TIMEOUT) { hasDesc("Layer: Outline", substring = true) }
    }

    @Test
    fun homeShowsActionsAndDemoOpensEditor() {
        rule.waitUntil(TIMEOUT) { has("Import photo") }
        rule.onNodeWithText("Import photo").assertExists()
        rule.onNodeWithText("Try demo").assertExists()
        openEditorWithDemo()
        rule.onNodeWithContentDescription("Animation progress").assertExists()
    }

    @Test
    fun layersPanelListsOutlineAndMyDrawing() {
        openEditorWithDemo()
        openLayers()
        rule.onNodeWithContentDescription("Layer: Outline", substring = true).assertExists()
        rule.onNodeWithContentDescription("Layer: My Drawing", substring = true).assertExists()
    }

    @Test
    fun outlinePlusShadingCreatesShadingLayer() {
        openEditorWithDemo()
        clickTopAction("Style")
        rule.waitUntil(TIMEOUT) { has("Outline + Shading") }
        rule.onNodeWithText("Outline + Shading").performClick()
        rule.onNodeWithText("Draw it").performClick()
        awaitGeneration()
        rule.waitUntil(TIMEOUT) { hasDesc("Show finished") }
        openLayers()
        rule.waitUntil(TIMEOUT) { hasDesc("Layer: Shading,", substring = true) }
        rule.onNodeWithContentDescription("Layer: Shading,", substring = true).assertExists()
    }

    @Test
    fun drawingWithPenEnablesUndo() {
        openEditorWithDemo()
        rule.onNodeWithContentDescription("Pen").performClick()
        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        rule.onRoot().performTouchInput {
            swipe(
                start = Offset(width * 0.3f, height * 0.45f),
                end = Offset(width * 0.7f, height * 0.5f),
                durationMillis = 400,
            )
        }
        rule.waitUntil(TIMEOUT) {
            rule.onAllNodesWithContentDescription("Undo").fetchSemanticsNodes()
                .any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        }
        rule.onNodeWithContentDescription("Undo").assertIsEnabled()
    }

    @Test
    fun exportSheetShowsVideoOption() {
        openEditorWithDemo()
        clickTopAction("Export")
        rule.waitUntil(TIMEOUT) { has("Video") }
        rule.onNodeWithText("Video").assertExists()
    }
}
