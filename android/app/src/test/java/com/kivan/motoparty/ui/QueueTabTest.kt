package com.kivan.motoparty.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.music.Track
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drag to reorder on a Pixel 8-sized screen, with a queue several screens long. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class QueueTabTest {
    @get:Rule
    val compose = createComposeRule()

    private val songs = (0 until 40).map { Track("id$it", "Song $it", "Artist", durationMs = 200_000) }
    private val status = LinkStatus(running = true, nowPlaying = Track("now", "Now", "Artist", durationMs = 200_000), playing = true, queue = songs)
    private val actions = mutableListOf<UiAction>()

    private fun show() {
        compose.setContent { MotopartyTheme { QueueTab(status, Callbacks(onAction = { actions += it }), onSearch = {}) } }
        compose.waitForIdle()
        // The edge scroll asks for a frame on every frame while a row is held: time moves only
        // when the test says so.
        compose.mainClock.autoAdvance = false
    }

    /** Holds [title]'s row, drags the finger to [toY] (px in the root) and keeps it there for [holdMs]. */
    private fun dragTo(title: String, toY: Float, holdMs: Long) {
        val start = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.center
        val longPress = compose.onRoot().fetchSemanticsNode().layoutInfo.viewConfiguration.longPressTimeoutMillis
        compose.onRoot().performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(longPress + 100)
        val steps = 20
        repeat(steps) { n ->
            compose.onRoot().performTouchInput { moveTo(Offset(start.x, start.y + (toY - start.y) * (n + 1) / steps)) }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeBy(holdMs)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun rootHeight() = compose.onRoot().fetchSemanticsNode().boundsInRoot.height

    @Test
    fun heldAtTheBottomEdgeTheRowGoesPastTheScreenToTheEnd() {
        show()
        dragTo("Song 0", rootHeight() - 4f, holdMs = 8_000)
        // The list scrolled under the row to its end and stopped there; the row stayed under the
        // finger the whole way (a lost row would have ended the drag one screen down).
        assertEquals(listOf(UiAction.Move(0, "id0", 39)), actions)
    }

    @Test
    fun heldAtTheTopEdgeTheRowGoesBackToTheStart() {
        show()
        // Three rows before the queue: "Now playing", the song, "Up next".
        compose.onNode(hasScrollAction()).performScrollToIndex(3 + 39)
        compose.mainClock.advanceTimeByFrame()
        dragTo("Song 39", 4f, holdMs = 8_000)
        assertEquals(listOf(UiAction.Move(39, "id39", 0)), actions)
    }

    @Test
    fun aRowHeldAwayFromTheEdgesDoesNotScroll() {
        show()
        val row = compose.onNodeWithText("Song 3").fetchSemanticsNode().boundsInRoot
        // Down by about one and a half rows: one place, however long it is held.
        dragTo("Song 3", row.center.y + row.height * 1.5f, holdMs = 3_000)
        assertEquals(listOf(UiAction.Move(3, "id3", 4)), actions)
    }
}
