package com.editor.veyro

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import com.editor.veyro.core.designsystem.component.LocalThumbnailProvider
import com.editor.veyro.core.designsystem.component.ThumbnailKind
import com.editor.veyro.core.designsystem.component.ThumbnailProvider
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.designsystem.theme.VeyroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The UI is always dark, so system bar icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as VeyroApplication).container
        setContent {
            val thumbnails = remember {
                ThumbnailProvider { request ->
                    val kind = when (request.kind) {
                        ThumbnailKind.VIDEO -> MediaKind.VIDEO
                        ThumbnailKind.IMAGE -> MediaKind.IMAGE
                        ThumbnailKind.AUDIO -> MediaKind.AUDIO
                    }
                    container.thumbnails.load(request.uri, kind, request.sizePx)?.asImageBitmap()
                }
            }
            VeyroTheme {
                CompositionLocalProvider(LocalThumbnailProvider provides thumbnails) {
                    VeyroApp(container)
                }
            }
        }
    }
}
