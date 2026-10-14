package com.editor.veyro.media.playback.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleStartEffect
import com.editor.veyro.media.playback.PreviewController

/**
 * Binds [controller] to the screen's lifecycle:
 * - attaches the player when the screen starts
 * - detaches (releasing decoders) when it stops for real
 *
 * A configuration change (rotation, resize, dark mode) also stops and
 * restarts the Activity. The player is kept through it, so playback doesn't
 * stutter or rebuffer. The controller's owner releases it for good when the
 * screen leaves the back stack.
 */
@Composable
public fun BindPreviewLifecycle(controller: PreviewController) {
    val activity = LocalActivity.current
    LifecycleStartEffect(controller) {
        controller.attach()
        onStopOrDispose {
            if (activity?.isChangingConfigurations != true) controller.detach()
        }
    }
}
