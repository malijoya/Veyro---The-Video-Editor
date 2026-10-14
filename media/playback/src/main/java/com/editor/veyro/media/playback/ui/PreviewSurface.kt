package com.editor.veyro.media.playback.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW

/**
 * Renders [player]'s video, letterboxed to keep its aspect ratio
 * (`ContentScale.Fit`).
 *
 * - The aspect ratio comes from the decoded video size, including pixel
 *   aspect ratio and rotation, so portrait phone clips show upright.
 * - Until the first frame renders, a black shutter shows instead of a
 *   stretched or stale frame.
 * - Uses a SurfaceView, the most power-efficient path, which also supports
 *   HDR output.
 *
 * `ContentFrame` is `@UnstableApi` in Media3 1.11. Its use is confined to this
 * file, so an API change touches one place.
 */
@OptIn(UnstableApi::class)
@Composable
public fun PreviewSurface(player: Player?, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            contentScale = ContentScale.Fit,
            keepContentOnReset = false,
        )
    }
}
