package com.editor.veyro.core.designsystem.icon

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes

/**
 * Domain icons not included in material-icons-core.
 *
 * Generated from the Material Icons sources in
 * androidx.compose.material:material-icons-extended 1.7.8 (Apache License 2.0).
 * Only the few icons Veyro needs are copied, so the large extended artifact is
 * not a dependency.
 */
public object VeyroIcons {
    public val VideoLibraryFilled: ImageVector
        get() {
            _videoLibraryFilled?.let { return it }
            return materialIcon(name = "Veyro.VideoLibraryFilled") {
                materialPath {
                    moveTo(4.0f, 6.0f)
                    lineTo(2.0f, 6.0f)
                    verticalLineToRelative(14.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(4.0f, 20.0f)
                    lineTo(4.0f, 6.0f)
                    close()
                    moveTo(20.0f, 2.0f)
                    lineTo(8.0f, 2.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(12.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(12.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(22.0f, 4.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(12.0f, 14.5f)
                    verticalLineToRelative(-9.0f)
                    lineToRelative(6.0f, 4.5f)
                    lineToRelative(-6.0f, 4.5f)
                    close()
                }
            }.also { _videoLibraryFilled = it }
        }
    private var _videoLibraryFilled: ImageVector? = null

    public val VideoLibrary: ImageVector
        get() {
            _videoLibrary?.let { return it }
            return materialIcon(name = "Veyro.VideoLibrary") {
                materialPath {
                    moveTo(4.0f, 6.0f)
                    lineTo(2.0f, 6.0f)
                    verticalLineToRelative(14.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(4.0f, 20.0f)
                    lineTo(4.0f, 6.0f)
                    close()
                    moveTo(20.0f, 2.0f)
                    lineTo(8.0f, 2.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(12.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(12.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(22.0f, 4.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(20.0f, 16.0f)
                    lineTo(8.0f, 16.0f)
                    lineTo(8.0f, 4.0f)
                    horizontalLineToRelative(12.0f)
                    verticalLineToRelative(12.0f)
                    close()
                    moveTo(12.0f, 5.5f)
                    verticalLineToRelative(9.0f)
                    lineToRelative(6.0f, -4.5f)
                    close()
                }
            }.also { _videoLibrary = it }
        }
    private var _videoLibrary: ImageVector? = null

    public val FolderOpen: ImageVector
        get() {
            _folderOpen?.let { return it }
            return materialIcon(name = "Veyro.FolderOpen") {
                materialPath {
                    moveTo(20.0f, 6.0f)
                    horizontalLineToRelative(-8.0f)
                    lineToRelative(-2.0f, -2.0f)
                    lineTo(4.0f, 4.0f)
                    curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
                    lineTo(2.0f, 18.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(16.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(22.0f, 8.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(20.0f, 18.0f)
                    lineTo(4.0f, 18.0f)
                    lineTo(4.0f, 8.0f)
                    horizontalLineToRelative(16.0f)
                    verticalLineToRelative(10.0f)
                    close()
                }
            }.also { _folderOpen = it }
        }
    private var _folderOpen: ImageVector? = null

    public val PhotoLibrary: ImageVector
        get() {
            _photoLibrary?.let { return it }
            return materialIcon(name = "Veyro.PhotoLibrary") {
                materialPath {
                    moveTo(20.0f, 4.0f)
                    verticalLineToRelative(12.0f)
                    lineTo(8.0f, 16.0f)
                    lineTo(8.0f, 4.0f)
                    horizontalLineToRelative(12.0f)
                    moveToRelative(0.0f, -2.0f)
                    lineTo(8.0f, 2.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(12.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(12.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(22.0f, 4.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(11.5f, 11.67f)
                    lineToRelative(1.69f, 2.26f)
                    lineToRelative(2.48f, -3.1f)
                    lineTo(19.0f, 15.0f)
                    lineTo(9.0f, 15.0f)
                    close()
                    moveTo(2.0f, 6.0f)
                    verticalLineToRelative(14.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(4.0f, 20.0f)
                    lineTo(4.0f, 6.0f)
                    lineTo(2.0f, 6.0f)
                    close()
                }
            }.also { _photoLibrary = it }
        }
    private var _photoLibrary: ImageVector? = null

    public val Movie: ImageVector
        get() {
            _movie?.let { return it }
            return materialIcon(name = "Veyro.Movie") {
                materialPath {
                    moveTo(4.0f, 6.47f)
                    lineTo(5.76f, 10.0f)
                    horizontalLineTo(20.0f)
                    verticalLineToRelative(8.0f)
                    horizontalLineTo(4.0f)
                    verticalLineTo(6.47f)
                    moveTo(22.0f, 4.0f)
                    horizontalLineToRelative(-4.0f)
                    lineToRelative(2.0f, 4.0f)
                    horizontalLineToRelative(-3.0f)
                    lineToRelative(-2.0f, -4.0f)
                    horizontalLineToRelative(-2.0f)
                    lineToRelative(2.0f, 4.0f)
                    horizontalLineToRelative(-3.0f)
                    lineToRelative(-2.0f, -4.0f)
                    horizontalLineTo(8.0f)
                    lineToRelative(2.0f, 4.0f)
                    horizontalLineTo(7.0f)
                    lineTo(5.0f, 4.0f)
                    horizontalLineTo(4.0f)
                    curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
                    lineTo(2.0f, 18.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(16.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    verticalLineTo(4.0f)
                    close()
                }
            }.also { _movie = it }
        }
    private var _movie: ImageVector? = null

    public val BrokenImage: ImageVector
        get() {
            _brokenImage?.let { return it }
            return materialIcon(name = "Veyro.BrokenImage") {
                materialPath {
                    moveTo(19.0f, 3.0f)
                    lineTo(5.0f, 3.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(14.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(14.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(21.0f, 5.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(19.0f, 19.0f)
                    lineTo(5.0f, 19.0f)
                    verticalLineToRelative(-4.58f)
                    lineToRelative(0.99f, 0.99f)
                    lineToRelative(4.0f, -4.0f)
                    lineToRelative(4.0f, 4.0f)
                    lineToRelative(4.0f, -3.99f)
                    lineTo(19.0f, 12.43f)
                    lineTo(19.0f, 19.0f)
                    close()
                    moveTo(19.0f, 9.59f)
                    lineToRelative(-1.01f, -1.01f)
                    lineToRelative(-4.0f, 4.01f)
                    lineToRelative(-4.0f, -4.0f)
                    lineToRelative(-4.0f, 4.0f)
                    lineToRelative(-0.99f, -1.0f)
                    lineTo(5.0f, 5.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(4.59f)
                    close()
                }
            }.also { _brokenImage = it }
        }
    private var _brokenImage: ImageVector? = null

    public val Storage: ImageVector
        get() {
            _storage?.let { return it }
            return materialIcon(name = "Veyro.Storage") {
                materialPath {
                    moveTo(2.0f, 20.0f)
                    horizontalLineToRelative(20.0f)
                    verticalLineToRelative(-4.0f)
                    lineTo(2.0f, 16.0f)
                    verticalLineToRelative(4.0f)
                    close()
                    moveTo(4.0f, 17.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    lineTo(4.0f, 19.0f)
                    verticalLineToRelative(-2.0f)
                    close()
                    moveTo(2.0f, 4.0f)
                    verticalLineToRelative(4.0f)
                    horizontalLineToRelative(20.0f)
                    lineTo(22.0f, 4.0f)
                    lineTo(2.0f, 4.0f)
                    close()
                    moveTo(6.0f, 7.0f)
                    lineTo(4.0f, 7.0f)
                    lineTo(4.0f, 5.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(2.0f, 14.0f)
                    horizontalLineToRelative(20.0f)
                    verticalLineToRelative(-4.0f)
                    lineTo(2.0f, 10.0f)
                    verticalLineToRelative(4.0f)
                    close()
                    moveTo(4.0f, 11.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    lineTo(4.0f, 13.0f)
                    verticalLineToRelative(-2.0f)
                    close()
                }
            }.also { _storage = it }
        }
    private var _storage: ImageVector? = null

    public val HighQuality: ImageVector
        get() {
            _highQuality?.let { return it }
            return materialIcon(name = "Veyro.HighQuality") {
                materialPath {
                    moveTo(19.0f, 4.0f)
                    lineTo(5.0f, 4.0f)
                    curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(12.0f)
                    curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(14.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(21.0f, 6.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(19.0f, 18.0f)
                    lineTo(5.0f, 18.0f)
                    lineTo(5.0f, 6.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(12.0f)
                    close()
                    moveTo(7.5f, 13.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    lineTo(11.0f, 15.0f)
                    lineTo(11.0f, 9.0f)
                    lineTo(9.5f, 9.0f)
                    verticalLineToRelative(2.5f)
                    horizontalLineToRelative(-2.0f)
                    lineTo(7.5f, 9.0f)
                    lineTo(6.0f, 9.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineToRelative(1.5f)
                    close()
                    moveTo(14.0f, 15.0f)
                    horizontalLineToRelative(0.75f)
                    verticalLineToRelative(1.5f)
                    horizontalLineToRelative(1.5f)
                    lineTo(16.25f, 15.0f)
                    lineTo(17.0f, 15.0f)
                    curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                    verticalLineToRelative(-4.0f)
                    curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                    horizontalLineToRelative(-3.0f)
                    curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                    verticalLineToRelative(4.0f)
                    curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                    close()
                    moveTo(14.5f, 10.5f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-3.0f)
                    close()
                }
            }.also { _highQuality = it }
        }
    private var _highQuality: ImageVector? = null

    public val MusicNote: ImageVector
        get() {
            _musicNote?.let { return it }
            return materialIcon(name = "Veyro.MusicNote") {
                materialPath {
                    moveTo(12.0f, 3.0f)
                    lineToRelative(0.01f, 10.55f)
                    curveToRelative(-0.59f, -0.34f, -1.27f, -0.55f, -2.0f, -0.55f)
                    curveTo(7.79f, 13.0f, 6.0f, 14.79f, 6.0f, 17.0f)
                    reflectiveCurveToRelative(1.79f, 4.0f, 4.01f, 4.0f)
                    reflectiveCurveTo(14.0f, 19.21f, 14.0f, 17.0f)
                    lineTo(14.0f, 7.0f)
                    horizontalLineToRelative(4.0f)
                    lineTo(18.0f, 3.0f)
                    horizontalLineToRelative(-6.0f)
                    close()
                    moveTo(10.01f, 19.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                    reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                    reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                    reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                    close()
                }
            }.also { _musicNote = it }
        }
    private var _musicNote: ImageVector? = null

    public val LibraryMusic: ImageVector
        get() {
            _libraryMusic?.let { return it }
            return materialIcon(name = "Veyro.LibraryMusic") {
                materialPath {
                    moveTo(20.0f, 2.0f)
                    lineTo(8.0f, 2.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(12.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(12.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(22.0f, 4.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(20.0f, 16.0f)
                    lineTo(8.0f, 16.0f)
                    lineTo(8.0f, 4.0f)
                    horizontalLineToRelative(12.0f)
                    verticalLineToRelative(12.0f)
                    close()
                    moveTo(12.5f, 15.0f)
                    curveToRelative(1.38f, 0.0f, 2.5f, -1.12f, 2.5f, -2.5f)
                    lineTo(15.0f, 7.0f)
                    horizontalLineToRelative(3.0f)
                    lineTo(18.0f, 5.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(5.51f)
                    curveToRelative(-0.42f, -0.32f, -0.93f, -0.51f, -1.5f, -0.51f)
                    curveToRelative(-1.38f, 0.0f, -2.5f, 1.12f, -2.5f, 2.5f)
                    reflectiveCurveToRelative(1.12f, 2.5f, 2.5f, 2.5f)
                    close()
                    moveTo(4.0f, 6.0f)
                    lineTo(2.0f, 6.0f)
                    verticalLineToRelative(14.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(4.0f, 20.0f)
                    lineTo(4.0f, 6.0f)
                    close()
                }
            }.also { _libraryMusic = it }
        }
    private var _libraryMusic: ImageVector? = null

    public val Pause: ImageVector
        get() {
            _pause?.let { return it }
            return materialIcon(name = "Veyro.Pause") {
                materialPath {
                    moveTo(6.0f, 19.0f)
                    horizontalLineToRelative(4.0f)
                    lineTo(10.0f, 5.0f)
                    lineTo(6.0f, 5.0f)
                    verticalLineToRelative(14.0f)
                    close()
                    moveTo(14.0f, 5.0f)
                    verticalLineToRelative(14.0f)
                    horizontalLineToRelative(4.0f)
                    lineTo(18.0f, 5.0f)
                    horizontalLineToRelative(-4.0f)
                    close()
                }
            }.also { _pause = it }
        }
    private var _pause: ImageVector? = null

    public val Replay: ImageVector
        get() {
            _replay?.let { return it }
            return materialIcon(name = "Veyro.Replay") {
                materialPath {
                    moveTo(12.0f, 5.0f)
                    verticalLineTo(1.0f)
                    lineTo(7.0f, 6.0f)
                    lineToRelative(5.0f, 5.0f)
                    verticalLineTo(7.0f)
                    curveToRelative(3.31f, 0.0f, 6.0f, 2.69f, 6.0f, 6.0f)
                    reflectiveCurveToRelative(-2.69f, 6.0f, -6.0f, 6.0f)
                    reflectiveCurveToRelative(-6.0f, -2.69f, -6.0f, -6.0f)
                    horizontalLineTo(4.0f)
                    curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
                    reflectiveCurveToRelative(8.0f, -3.58f, 8.0f, -8.0f)
                    reflectiveCurveToRelative(-3.58f, -8.0f, -8.0f, -8.0f)
                    close()
                }
            }.also { _replay = it }
        }
    private var _replay: ImageVector? = null

    public val Replay5: ImageVector
        get() {
            _replay5?.let { return it }
            return materialIcon(name = "Veyro.Replay5") {
                materialPath {
                    moveTo(12.0f, 5.0f)
                    verticalLineTo(1.0f)
                    lineTo(7.0f, 6.0f)
                    lineToRelative(5.0f, 5.0f)
                    verticalLineTo(7.0f)
                    curveToRelative(3.31f, 0.0f, 6.0f, 2.69f, 6.0f, 6.0f)
                    reflectiveCurveToRelative(-2.69f, 6.0f, -6.0f, 6.0f)
                    reflectiveCurveToRelative(-6.0f, -2.69f, -6.0f, -6.0f)
                    horizontalLineTo(4.0f)
                    curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
                    reflectiveCurveToRelative(8.0f, -3.58f, 8.0f, -8.0f)
                    reflectiveCurveTo(16.42f, 5.0f, 12.0f, 5.0f)
                    close()
                }
                materialPath {
                    moveTo(10.69f, 13.9f)
                    lineToRelative(0.25f, -2.17f)
                    horizontalLineToRelative(2.39f)
                    verticalLineToRelative(0.71f)
                    horizontalLineToRelative(-1.7f)
                    lineToRelative(-0.11f, 0.92f)
                    curveToRelative(0.03f, -0.02f, 0.07f, -0.03f, 0.11f, -0.05f)
                    reflectiveCurveToRelative(0.09f, -0.04f, 0.15f, -0.05f)
                    reflectiveCurveToRelative(0.12f, -0.03f, 0.18f, -0.04f)
                    reflectiveCurveToRelative(0.13f, -0.02f, 0.2f, -0.02f)
                    curveToRelative(0.21f, 0.0f, 0.39f, 0.03f, 0.55f, 0.1f)
                    reflectiveCurveToRelative(0.3f, 0.16f, 0.41f, 0.28f)
                    reflectiveCurveToRelative(0.2f, 0.27f, 0.25f, 0.45f)
                    reflectiveCurveToRelative(0.09f, 0.38f, 0.09f, 0.6f)
                    curveToRelative(0.0f, 0.19f, -0.03f, 0.37f, -0.09f, 0.54f)
                    reflectiveCurveToRelative(-0.15f, 0.32f, -0.27f, 0.45f)
                    reflectiveCurveToRelative(-0.27f, 0.24f, -0.45f, 0.31f)
                    reflectiveCurveToRelative(-0.39f, 0.12f, -0.64f, 0.12f)
                    curveToRelative(-0.18f, 0.0f, -0.36f, -0.03f, -0.53f, -0.08f)
                    reflectiveCurveToRelative(-0.32f, -0.14f, -0.46f, -0.24f)
                    reflectiveCurveToRelative(-0.24f, -0.24f, -0.32f, -0.39f)
                    reflectiveCurveToRelative(-0.13f, -0.33f, -0.13f, -0.53f)
                    horizontalLineToRelative(0.84f)
                    curveToRelative(0.02f, 0.18f, 0.08f, 0.32f, 0.19f, 0.41f)
                    reflectiveCurveToRelative(0.25f, 0.15f, 0.42f, 0.15f)
                    curveToRelative(0.11f, 0.0f, 0.2f, -0.02f, 0.27f, -0.06f)
                    reflectiveCurveToRelative(0.14f, -0.1f, 0.18f, -0.17f)
                    reflectiveCurveToRelative(0.08f, -0.15f, 0.11f, -0.25f)
                    reflectiveCurveToRelative(0.03f, -0.2f, 0.03f, -0.31f)
                    reflectiveCurveToRelative(-0.01f, -0.21f, -0.04f, -0.31f)
                    reflectiveCurveToRelative(-0.07f, -0.17f, -0.13f, -0.24f)
                    reflectiveCurveToRelative(-0.13f, -0.12f, -0.21f, -0.15f)
                    reflectiveCurveToRelative(-0.19f, -0.05f, -0.3f, -0.05f)
                    curveToRelative(-0.08f, 0.0f, -0.15f, 0.01f, -0.2f, 0.02f)
                    reflectiveCurveToRelative(-0.11f, 0.03f, -0.15f, 0.05f)
                    reflectiveCurveToRelative(-0.08f, 0.05f, -0.12f, 0.07f)
                    reflectiveCurveToRelative(-0.07f, 0.06f, -0.1f, 0.09f)
                    lineTo(10.69f, 13.9f)
                    close()
                }
            }.also { _replay5 = it }
        }
    private var _replay5: ImageVector? = null

    public val Forward5: ImageVector
        get() {
            _forward5?.let { return it }
            return materialIcon(name = "Veyro.Forward5") {
                materialPath {
                    moveTo(18.0f, 13.0f)
                    curveToRelative(0.0f, 3.31f, -2.69f, 6.0f, -6.0f, 6.0f)
                    reflectiveCurveToRelative(-6.0f, -2.69f, -6.0f, -6.0f)
                    reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
                    verticalLineToRelative(4.0f)
                    lineToRelative(5.0f, -5.0f)
                    lineToRelative(-5.0f, -5.0f)
                    verticalLineToRelative(4.0f)
                    curveToRelative(-4.42f, 0.0f, -8.0f, 3.58f, -8.0f, 8.0f)
                    curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
                    curveToRelative(4.42f, 0.0f, 8.0f, -3.58f, 8.0f, -8.0f)
                    horizontalLineTo(18.0f)
                    close()
                }
                materialPath {
                    moveTo(12.03f, 15.38f)
                    curveToRelative(-0.44f, 0.0f, -0.58f, -0.31f, -0.6f, -0.56f)
                    horizontalLineToRelative(-0.84f)
                    curveToRelative(0.03f, 0.85f, 0.79f, 1.25f, 1.44f, 1.25f)
                    curveToRelative(0.93f, 0.0f, 1.44f, -0.63f, 1.44f, -1.43f)
                    curveToRelative(0.0f, -1.33f, -0.97f, -1.44f, -1.3f, -1.44f)
                    curveToRelative(-0.2f, 0.0f, -0.43f, 0.05f, -0.64f, 0.16f)
                    lineToRelative(0.11f, -0.92f)
                    horizontalLineToRelative(1.7f)
                    verticalLineToRelative(-0.71f)
                    horizontalLineToRelative(-2.39f)
                    lineToRelative(-0.25f, 2.17f)
                    lineToRelative(0.67f, 0.17f)
                    curveToRelative(0.13f, -0.13f, 0.28f, -0.23f, 0.57f, -0.23f)
                    curveToRelative(0.4f, 0.0f, 0.69f, 0.23f, 0.69f, 0.75f)
                    curveTo(12.62f, 14.64f, 12.65f, 15.38f, 12.03f, 15.38f)
                    close()
                }
            }.also { _forward5 = it }
        }
    private var _forward5: ImageVector? = null

    public val ZoomIn: ImageVector
        get() {
            _zoomIn?.let { return it }
            return materialIcon(name = "Veyro.ZoomIn") {
                materialPath {
                    moveTo(15.5f, 14.0f)
                    horizontalLineToRelative(-0.79f)
                    lineToRelative(-0.28f, -0.27f)
                    curveTo(15.41f, 12.59f, 16.0f, 11.11f, 16.0f, 9.5f)
                    curveTo(16.0f, 5.91f, 13.09f, 3.0f, 9.5f, 3.0f)
                    reflectiveCurveTo(3.0f, 5.91f, 3.0f, 9.5f)
                    reflectiveCurveTo(5.91f, 16.0f, 9.5f, 16.0f)
                    curveToRelative(1.61f, 0.0f, 3.09f, -0.59f, 4.23f, -1.57f)
                    lineToRelative(0.27f, 0.28f)
                    verticalLineToRelative(0.79f)
                    lineToRelative(5.0f, 4.99f)
                    lineTo(20.49f, 19.0f)
                    lineToRelative(-4.99f, -5.0f)
                    close()
                    moveTo(9.5f, 14.0f)
                    curveTo(7.01f, 14.0f, 5.0f, 11.99f, 5.0f, 9.5f)
                    reflectiveCurveTo(7.01f, 5.0f, 9.5f, 5.0f)
                    reflectiveCurveTo(14.0f, 7.01f, 14.0f, 9.5f)
                    reflectiveCurveTo(11.99f, 14.0f, 9.5f, 14.0f)
                    close()
                    moveTo(10.0f, 7.0f)
                    lineTo(9.0f, 7.0f)
                    verticalLineToRelative(2.0f)
                    lineTo(7.0f, 9.0f)
                    verticalLineToRelative(1.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(1.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2.0f)
                    lineTo(12.0f, 9.0f)
                    horizontalLineToRelative(-2.0f)
                    close()
                }
            }.also { _zoomIn = it }
        }
    private var _zoomIn: ImageVector? = null

    public val ZoomOut: ImageVector
        get() {
            _zoomOut?.let { return it }
            return materialIcon(name = "Veyro.ZoomOut") {
                materialPath {
                    moveTo(15.5f, 14.0f)
                    horizontalLineToRelative(-0.79f)
                    lineToRelative(-0.28f, -0.27f)
                    curveTo(15.41f, 12.59f, 16.0f, 11.11f, 16.0f, 9.5f)
                    curveTo(16.0f, 5.91f, 13.09f, 3.0f, 9.5f, 3.0f)
                    reflectiveCurveTo(3.0f, 5.91f, 3.0f, 9.5f)
                    reflectiveCurveTo(5.91f, 16.0f, 9.5f, 16.0f)
                    curveToRelative(1.61f, 0.0f, 3.09f, -0.59f, 4.23f, -1.57f)
                    lineToRelative(0.27f, 0.28f)
                    verticalLineToRelative(0.79f)
                    lineToRelative(5.0f, 4.99f)
                    lineTo(20.49f, 19.0f)
                    lineToRelative(-4.99f, -5.0f)
                    close()
                    moveTo(9.5f, 14.0f)
                    curveTo(7.01f, 14.0f, 5.0f, 11.99f, 5.0f, 9.5f)
                    reflectiveCurveTo(7.01f, 5.0f, 9.5f, 5.0f)
                    reflectiveCurveTo(14.0f, 7.01f, 14.0f, 9.5f)
                    reflectiveCurveTo(11.99f, 14.0f, 9.5f, 14.0f)
                    close()
                    moveTo(7.0f, 9.0f)
                    horizontalLineToRelative(5.0f)
                    verticalLineToRelative(1.0f)
                    lineTo(7.0f, 10.0f)
                    close()
                }
            }.also { _zoomOut = it }
        }
    private var _zoomOut: ImageVector? = null

    public val FitScreen: ImageVector
        get() {
            _fitScreen?.let { return it }
            return materialIcon(name = "Veyro.FitScreen") {
                materialPath {
                    moveTo(17.0f, 4.0f)
                    horizontalLineToRelative(3.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-2.0f)
                    lineTo(20.0f, 6.0f)
                    horizontalLineToRelative(-3.0f)
                    lineTo(17.0f, 4.0f)
                    close()
                    moveTo(4.0f, 8.0f)
                    lineTo(4.0f, 6.0f)
                    horizontalLineToRelative(3.0f)
                    lineTo(7.0f, 4.0f)
                    lineTo(4.0f, 4.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(2.0f)
                    close()
                    moveTo(20.0f, 16.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-3.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(3.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(-2.0f)
                    close()
                    moveTo(7.0f, 18.0f)
                    lineTo(4.0f, 18.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(2.0f, 16.0f)
                    verticalLineToRelative(2.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineToRelative(-2.0f)
                    close()
                    moveTo(18.0f, 8.0f)
                    lineTo(6.0f, 8.0f)
                    verticalLineToRelative(8.0f)
                    horizontalLineToRelative(12.0f)
                    lineTo(18.0f, 8.0f)
                    close()
                }
            }.also { _fitScreen = it }
        }
    private var _fitScreen: ImageVector? = null

    public val ContentCut: ImageVector
        get() {
            _contentCut?.let { return it }
            return materialIcon(name = "Veyro.ContentCut") {
                materialPath {
                    moveTo(9.64f, 7.64f)
                    curveToRelative(0.23f, -0.5f, 0.36f, -1.05f, 0.36f, -1.64f)
                    curveToRelative(0.0f, -2.21f, -1.79f, -4.0f, -4.0f, -4.0f)
                    reflectiveCurveTo(2.0f, 3.79f, 2.0f, 6.0f)
                    reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
                    curveToRelative(0.59f, 0.0f, 1.14f, -0.13f, 1.64f, -0.36f)
                    lineTo(10.0f, 12.0f)
                    lineToRelative(-2.36f, 2.36f)
                    curveTo(7.14f, 14.13f, 6.59f, 14.0f, 6.0f, 14.0f)
                    curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
                    reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
                    reflectiveCurveToRelative(4.0f, -1.79f, 4.0f, -4.0f)
                    curveToRelative(0.0f, -0.59f, -0.13f, -1.14f, -0.36f, -1.64f)
                    lineTo(12.0f, 14.0f)
                    lineToRelative(7.0f, 7.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineToRelative(-1.0f)
                    lineTo(9.64f, 7.64f)
                    close()
                    moveTo(6.0f, 8.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, -0.89f, -2.0f, -2.0f)
                    reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                    reflectiveCurveToRelative(2.0f, 0.89f, 2.0f, 2.0f)
                    reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                    close()
                    moveTo(6.0f, 20.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, -0.89f, -2.0f, -2.0f)
                    reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                    reflectiveCurveToRelative(2.0f, 0.89f, 2.0f, 2.0f)
                    reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                    close()
                    moveTo(12.0f, 12.5f)
                    curveToRelative(-0.28f, 0.0f, -0.5f, -0.22f, -0.5f, -0.5f)
                    reflectiveCurveToRelative(0.22f, -0.5f, 0.5f, -0.5f)
                    reflectiveCurveToRelative(0.5f, 0.22f, 0.5f, 0.5f)
                    reflectiveCurveToRelative(-0.22f, 0.5f, -0.5f, 0.5f)
                    close()
                    moveTo(19.0f, 3.0f)
                    lineToRelative(-6.0f, 6.0f)
                    lineToRelative(2.0f, 2.0f)
                    lineToRelative(7.0f, -7.0f)
                    lineTo(22.0f, 3.0f)
                    close()
                }
            }.also { _contentCut = it }
        }
    private var _contentCut: ImageVector? = null

    public val Undo: ImageVector
        get() {
            _undo?.let { return it }
            return materialIcon(name = "Veyro.Undo", autoMirror = true) {
                materialPath {
                    moveTo(12.5f, 8.0f)
                    curveToRelative(-2.65f, 0.0f, -5.05f, 0.99f, -6.9f, 2.6f)
                    lineTo(2.0f, 7.0f)
                    verticalLineToRelative(9.0f)
                    horizontalLineToRelative(9.0f)
                    lineToRelative(-3.62f, -3.62f)
                    curveToRelative(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f)
                    curveToRelative(3.54f, 0.0f, 6.55f, 2.31f, 7.6f, 5.5f)
                    lineToRelative(2.37f, -0.78f)
                    curveTo(21.08f, 11.03f, 17.15f, 8.0f, 12.5f, 8.0f)
                    close()
                }
            }.also { _undo = it }
        }
    private var _undo: ImageVector? = null

    public val Redo: ImageVector
        get() {
            _redo?.let { return it }
            return materialIcon(name = "Veyro.Redo", autoMirror = true) {
                materialPath {
                    moveTo(18.4f, 10.6f)
                    curveTo(16.55f, 8.99f, 14.15f, 8.0f, 11.5f, 8.0f)
                    curveToRelative(-4.65f, 0.0f, -8.58f, 3.03f, -9.96f, 7.22f)
                    lineTo(3.9f, 16.0f)
                    curveToRelative(1.05f, -3.19f, 4.05f, -5.5f, 7.6f, -5.5f)
                    curveToRelative(1.95f, 0.0f, 3.73f, 0.72f, 5.12f, 1.88f)
                    lineTo(13.0f, 16.0f)
                    horizontalLineToRelative(9.0f)
                    verticalLineTo(7.0f)
                    lineToRelative(-3.6f, 3.6f)
                    close()
                }
            }.also { _redo = it }
        }
    private var _redo: ImageVector? = null

    public val VolumeUp: ImageVector
        get() {
            _volumeUp?.let { return it }
            return materialIcon(name = "Veyro.VolumeUp", autoMirror = true) {
                addPath(
                    pathData = addPathNodes(
                        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z" +
                            "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
                    ),
                    fill = SolidColor(Color.Black),
                )
            }.also { _volumeUp = it }
        }
    private var _volumeUp: ImageVector? = null

    public val VolumeOff: ImageVector
        get() {
            _volumeOff?.let { return it }
            return materialIcon(name = "Veyro.VolumeOff", autoMirror = true) {
                addPath(
                    pathData = addPathNodes(
                        "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63z" +
                            "M19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71z" +
                            "M4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3z" +
                            "M12,4L9.91,6.09 12,8.18V4z",
                    ),
                    fill = SolidColor(Color.Black),
                )
            }.also { _volumeOff = it }
        }
    private var _volumeOff: ImageVector? = null

    public val Mic: ImageVector
        get() {
            _mic?.let { return it }
            return materialIcon(name = "Veyro.Mic") {
                addPath(
                    pathData = addPathNodes(
                        "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                            "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
                    ),
                    fill = SolidColor(Color.Black),
                )
            }.also { _mic = it }
        }
    private var _mic: ImageVector? = null

    public val TextFields: ImageVector
        get() {
            _textFields?.let { return it }
            return materialIcon(name = "Veyro.TextFields") {
                addPath(
                    pathData = addPathNodes(
                        "M2.5,4v3h5v12h3V7h5V4H2.5zM21.5,9h-9v3h3v7h3v-7h3V9z",
                    ),
                    fill = SolidColor(Color.Black),
                )
            }.also { _textFields = it }
        }
    private var _textFields: ImageVector? = null

    public val Sticker: ImageVector
        get() {
            _sticker?.let { return it }
            return materialIcon(name = "Veyro.Sticker") {
                addPath(
                    pathData = addPathNodes(
                        "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM15.5,11c0.83,0 1.5,-0.67 1.5,-1.5S16.33,8 15.5,8 14,8.67 14,9.5s0.67,1.5 1.5,1.5zM8.5,11c0.83,0 1.5,-0.67 1.5,-1.5S9.33,8 8.5,8 7,8.67 7,9.5 7.67,11 8.5,11zM12,17.5c2.33,0 4.31,-1.46 5.11,-3.5L6.89,14c0.8,2.04 2.78,3.5 5.11,3.5z",
                    ),
                    fill = SolidColor(Color.Black),
                )
            }.also { _sticker = it }
        }
    private var _sticker: ImageVector? = null

    public val Layers: ImageVector
        get() {
            _layers?.let { return it }
            return materialIcon(name = "Veyro.Layers") {
                addPath(
                    pathData = addPathNodes(
                        "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z",
                    ),
                    fill = SolidColor(Color.Black),
                )
            }.also { _layers = it }
        }
    private var _layers: ImageVector? = null

    /** Material "swap_horiz": a transition between two clips. */
    public val Transition: ImageVector
        get() {
            _transition?.let { return it }
            return materialIcon(name = "Veyro.Transition") {
                addPath(
                    pathData = addPathNodes(
                        "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z",
                    ),
                    fill = SolidColor(Color.Black),
                )
            }.also { _transition = it }
        }
    private var _transition: ImageVector? = null

    public val AlignLeft: ImageVector
        get() {
            _alignLeft?.let { return it }
            return materialIcon(name = "Veyro.AlignLeft") {
                addPath(
                    pathData = addPathNodes(
                        "M15,15H3v2h12v-2zM15,7H3v2h12V7zM3,13h18v-2H3v2zM3,21h18v-2H3v2zM3,3v2h18V3H3z",
                    ),
                    fill = SolidColor(Color.Black),
                )
            }.also { _alignLeft = it }
        }
    private var _alignLeft: ImageVector? = null

    public val AlignCenter: ImageVector
        get() {
            _alignCenter?.let { return it }
            return materialIcon(name = "Veyro.AlignCenter") {
                addPath(
                    pathData = addPathNodes(
                        "M7,15v2h10v-2H7zM3,21h18v-2H3v2zM3,13h18v-2H3v2zM7,7v2h10V7H7zM3,3v2h18V3H3z",
                    ),
                    fill = SolidColor(Color.Black),
                )
            }.also { _alignCenter = it }
        }
    private var _alignCenter: ImageVector? = null

    public val AlignRight: ImageVector
        get() {
            _alignRight?.let { return it }
            return materialIcon(name = "Veyro.AlignRight") {
                addPath(
                    pathData = addPathNodes(
                        "M3,21h18v-2H3v2zM9,17h12v-2H9v2zM3,13h18v-2H3v2zM9,9h12V7H9v2zM3,3v2h18V3H3z",
                    ),
                    fill = SolidColor(Color.Black),
                )
            }.also { _alignRight = it }
        }
    private var _alignRight: ImageVector? = null

    /** A keyframe: a filled diamond. */
    public val Keyframe: ImageVector
        get() {
            _keyframe?.let { return it }
            return materialIcon(name = "Veyro.Keyframe") {
                addPath(
                    pathData = addPathNodes("M12,2L22,12L12,22L2,12Z"),
                    fill = SolidColor(Color.Black),
                )
            }.also { _keyframe = it }
        }
    private var _keyframe: ImageVector? = null

    /** No keyframe here yet: a diamond outline. */
    public val KeyframeOutline: ImageVector
        get() {
            _keyframeOutline?.let { return it }
            return materialIcon(name = "Veyro.KeyframeOutline") {
                addPath(
                    // Outer diamond clockwise, inner counter-clockwise, so the inside stays empty.
                    pathData = addPathNodes("M12,2L22,12L12,22L2,12ZM12,5L5,12L12,19L19,12Z"),
                    fill = SolidColor(Color.Black),
                )
            }.also { _keyframeOutline = it }
        }
    private var _keyframeOutline: ImageVector? = null
}
