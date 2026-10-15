package com.editor.veyro.media.render.transform

import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlMatrixTransformation
import androidx.media3.effect.RgbMatrix
import com.editor.veyro.core.model.transform.ClipPlacement
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.timeline.export.SegmentTransform

/**
 * A clip's placement over time as the renderer reads it: for each frame,
 * [at] evaluates the clip's transform and keyframes at the frame's
 * presentation time. The export sets it once. The editor preview replaces it
 * while a gesture is in progress, so the real render follows the finger
 * without rebuilding the composition. Thread-safe: written on the main
 * thread, read on Media3's GL thread.
 *
 * Media3 passes per-item effects the frame's composition time, the same
 * time the composition-level overlay sees: with one picture sequence, both
 * run in the same frame processor, after the input stage adds the item's
 * offset in the sequence (checked in the 1.11.1 sources,
 * `ExternalTextureManager`). Composition time is timeline time, because the
 * main track is packed from 0.
 */
public class LiveTransform(initial: SegmentTransform) {
    @Volatile
    public var value: SegmentTransform = initial

    /** The placement for the frame at composition time [presentationTimeUs]. */
    public fun at(presentationTimeUs: Long): ClipTransform = value.at(presentationTimeUs)
}

/**
 * The per-item video effects that place one clip on the output canvas:
 * crop, then placement (fit or fill, scale, rotation about the anchor,
 * position), then opacity. Media3 merges consecutive matrix and colour
 * stages into one shader pass, so this costs a single draw per frame.
 *
 * The frames these effects receive are already upright: Media3 has the
 * decoder apply a video's rotation metadata (and swaps the reported size),
 * and decodes images with their EXIF orientation applied. The decoded size
 * comes in through `configure`, so the layout uses the real frame, not the
 * project's stored metadata.
 *
 * The output is always the canvas size ([canvasWidthPx] × [canvasHeightPx]),
 * cleared to black where the picture doesn't reach.
 *
 * Each stage evaluates the placement at the frame's own presentation time,
 * so keyframed clips animate frame-exactly and identically in preview and
 * export, never by a clock.
 */
@OptIn(UnstableApi::class)
public object ClipTransformEffects {

    public fun create(transform: LiveTransform, canvasWidthPx: Int, canvasHeightPx: Int): List<Effect> =
        listOf(CropStage(transform), PlacementStage(transform, canvasWidthPx, canvasHeightPx), OpacityStage(transform))
}

/**
 * Maps the crop rectangle onto the whole frame, so the shader clips away
 * everything else. The frame keeps its size (the kept part is stretched to
 * it); [PlacementStage] undoes the stretch, and both run in the same pass.
 */
@OptIn(UnstableApi::class)
internal class CropStage(private val transform: LiveTransform) : GlMatrixTransformation {
    private var width = 1
    private var height = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray =
        ClipPlacement(transform.at(presentationTimeUs), width, height, width, height).cropMatrix()

    // Never a no-op: the preview may change the crop after Media3 has built the pipeline.
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

/** Lays the cropped picture out on the canvas, from the upright source size [CropStage] passes on. */
@OptIn(UnstableApi::class)
internal class PlacementStage(
    private val transform: LiveTransform,
    private val canvasWidthPx: Int,
    private val canvasHeightPx: Int,
) : GlMatrixTransformation {
    private var sourceWidth = 0
    private var sourceHeight = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        sourceWidth = inputWidth
        sourceHeight = inputHeight
        return Size(canvasWidthPx, canvasHeightPx)
    }

    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray =
        ClipPlacement(transform.at(presentationTimeUs), sourceWidth, sourceHeight, canvasWidthPx, canvasHeightPx).placementMatrix()

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

/**
 * Opacity over the black below the main track: the colour is scaled by the
 * opacity, in the linear light Media3 applies colour matrices in, which is
 * exactly blending with black. (Encoders drop alpha, so lowering alpha alone
 * would change nothing.)
 */
@OptIn(UnstableApi::class)
internal class OpacityStage(private val transform: LiveTransform) : RgbMatrix {

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val o = transform.at(presentationTimeUs).opacity
        return floatArrayOf(
            o, 0f, 0f, 0f,
            0f, o, 0f, 0f,
            0f, 0f, o, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}
