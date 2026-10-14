package com.editor.veyro.media.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.editor.veyro.core.designsystem.theme.VeyroTheme
import com.editor.veyro.media.playback.ui.PREVIEW_SKIP_US
import com.editor.veyro.media.playback.ui.PreviewControls
import com.editor.veyro.media.playback.ui.PreviewControlsTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewControlsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsTimeAndTogglesPlayPause() {
        var state by mutableStateOf(PreviewState(status = PreviewStatus.READY, positionUs = 12_000_000, durationUs = 65_000_000))
        var toggles = 0
        compose.setContent {
            VeyroTheme {
                PreviewControls(state, onPlayPause = { toggles++ }, onSeekTo = {}, onSeekBy = {})
            }
        }
        compose.onNodeWithTag(PreviewControlsTags.TIME).assertTextEquals("0:12 / 1:05")
        compose.onNodeWithTag(PreviewControlsTags.PLAY_PAUSE).assertIsEnabled().assertContentDescriptionEquals("Play")

        compose.onNodeWithTag(PreviewControlsTags.PLAY_PAUSE).performClick()
        assertEquals(1, toggles)

        state = state.copy(isPlaying = true)
        compose.onNodeWithTag(PreviewControlsTags.PLAY_PAUSE).assertContentDescriptionEquals("Pause")
        state = state.copy(status = PreviewStatus.ENDED, isPlaying = false)
        compose.onNodeWithTag(PreviewControlsTags.PLAY_PAUSE).assertContentDescriptionEquals("Replay")
    }

    @Test
    fun controlsAreDisabledWhileLoading() {
        compose.setContent {
            VeyroTheme {
                PreviewControls(PreviewState(status = PreviewStatus.LOADING), onPlayPause = {}, onSeekTo = {}, onSeekBy = {})
            }
        }
        compose.onNodeWithTag(PreviewControlsTags.PLAY_PAUSE).assertIsNotEnabled()
        compose.onNodeWithTag(PreviewControlsTags.SEEK_BAR).assertIsNotEnabled()
    }

    @Test
    fun skipButtonsSeekByFiveSeconds() {
        val seeks = mutableListOf<Long>()
        compose.setContent {
            VeyroTheme {
                PreviewControls(
                    PreviewState(status = PreviewStatus.READY, durationUs = 60_000_000),
                    onPlayPause = {}, onSeekTo = {}, onSeekBy = { seeks += it },
                )
            }
        }
        compose.onNodeWithContentDescription("Forward 5 seconds").performClick()
        compose.onNodeWithContentDescription("Back 5 seconds").performClick()
        assertEquals(listOf(PREVIEW_SKIP_US, -PREVIEW_SKIP_US), seeks)
    }
}
