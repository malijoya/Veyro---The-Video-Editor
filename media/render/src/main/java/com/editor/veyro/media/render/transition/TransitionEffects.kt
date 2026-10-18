package com.editor.veyro.media.render.transition

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.StaticOverlaySettings
import com.editor.veyro.core.model.transition.LayerLook
import com.editor.veyro.core.timeline.export.TransitionPlan

/**
 * Draws one layer of a transition: the last per-item stage of a clip that
 * takes part in one, after its crop, placement and opacity. For each frame
 * it asks [look] for the frame's [LayerLook] (at the frame's own
 * presentation time, which is timeline time) and draws exactly what
 * [com.editor.veyro.core.model.transition.TransitionComposite] describes:
 * sample the placed picture through the look's inverse map, scale the
 * colour by its brightness, and set the alpha to its opacity times the
 * wipe mask. Where the picture doesn't reach (black or transparent after
 * placement), the layer is opaque black, so both layers blend the same way
 * whichever is on top.
 *
 * Media3 cannot do this with its own effects: the compositor blends whole
 * inputs with one alpha and a matrix, which covers dissolves and moves but
 * not a wipe's moving edge or a fade's per-phase brightness, and its
 * compositor program can't be replaced. So the transition is shaped here,
 * per item, and the compositor only stacks the layers.
 *
 * Output size is the canvas, like the input from the placement stage.
 */
@OptIn(UnstableApi::class)
internal class TransitionStage(
    private val look: (Long) -> LayerLook,
    private val canvasWidthPx: Int,
    private val canvasHeightPx: Int,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            TransitionShaderProgram(look, canvasWidthPx, canvasHeightPx, useHdr)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

@OptIn(UnstableApi::class)
private class TransitionShaderProgram(
    private val look: (Long) -> LayerLook,
    private val canvasWidthPx: Int,
    private val canvasHeightPx: Int,
    useHdr: Boolean,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ useHdr, /* texturePoolCapacity = */ 1) {

    private val program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), COORDINATES_PER_VERTEX)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(canvasWidthPx, canvasHeightPx)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val look = look(presentationTimeUs)
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
            val inverse = look.inverse
            program.setFloatsUniform("uInverseX", floatArrayOf(inverse.a.toFloat(), inverse.b.toFloat(), inverse.tx.toFloat()))
            program.setFloatsUniform("uInverseY", floatArrayOf(inverse.c.toFloat(), inverse.d.toFloat(), inverse.ty.toFloat()))
            program.setFloatUniform("uOpacity", look.opacity.toFloat())
            program.setFloatUniform("uBrightness", look.brightness.toFloat())
            program.setFloatUniform("uOpaqueOutside", if (look.opaqueOutside) 1f else 0f)
            val wipe = look.wipe
            if (wipe == null) {
                program.setFloatsUniform("uWipe", floatArrayOf(0f, 0f, 0f))
                program.setFloatsUniform("uWipeEdge", floatArrayOf(0f, 1f, WIPE_NONE))
            } else {
                program.setFloatsUniform("uWipe", floatArrayOf(wipe.dirX.toFloat(), wipe.dirY.toFloat(), wipe.offset.toFloat()))
                program.setFloatsUniform("uWipeEdge", floatArrayOf(wipe.low.toFloat(), wipe.high.toFloat(), if (wipe.revealed) WIPE_REVEALED else WIPE_COVERED))
            }
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val COORDINATES_PER_VERTEX = 4
        const val WIPE_NONE = 0f
        const val WIPE_COVERED = 1f
        const val WIPE_REVEALED = 2f

        // Full-screen quad; texture coordinates have y up, canvas fractions y down.
        const val VERTEX_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        // Mirrors TransitionComposite.layer: inverse map, brightness, opacity times the wipe's alpha, black outside the picture.
        const val FRAGMENT_SHADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec3 uInverseX;
uniform vec3 uInverseY;
uniform float uOpacity;
uniform float uBrightness;
uniform float uOpaqueOutside;
uniform vec3 uWipe;
uniform vec3 uWipeEdge;
varying vec2 vTexSamplingCoord;
void main() {
  vec3 canvas = vec3(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y, 1.0);
  vec2 picture = vec2(dot(uInverseX, canvas), dot(uInverseY, canvas));
  if (picture.x < 0.0 || picture.x > 1.0 || picture.y < 0.0 || picture.y > 1.0) {
    gl_FragColor = vec4(0.0, 0.0, 0.0, uOpaqueOutside * uOpacity);
    return;
  }
  float mask = 1.0;
  if (uWipeEdge.z > 0.5) {
    float coverage = smoothstep(uWipeEdge.x, uWipeEdge.y, uWipe.x * canvas.x + uWipe.y * canvas.y + uWipe.z);
    mask = uWipeEdge.z > 1.5 ? 1.0 - coverage : coverage;
  }
  vec4 color = texture2D(uTexSampler, vec2(picture.x, 1.0 - picture.y));
  gl_FragColor = vec4(color.rgb * uBrightness, uOpacity * mask);
}
"""
    }
}

/**
 * Stacks the composition's two picture inputs: the main track (input 0,
 * which Media3 draws on top) over the handle layer. The handle layer shows
 * only inside its pieces; its gaps (Media3 fills them with blank frames)
 * are fully transparent. Every input already has the canvas size, so the
 * output is the canvas.
 *
 * Media3 asks for each input frame's settings with that frame's own
 * timestamp, when the frame arrives.
 */
@OptIn(UnstableApi::class)
internal class TransitionCompositorSettings(
    private val plan: TransitionPlan,
    private val canvasWidthPx: Int,
    private val canvasHeightPx: Int,
) : VideoCompositorSettings {

    override fun getOutputSize(inputSizes: List<Size>): Size = Size(canvasWidthPx, canvasHeightPx)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
        if (inputId == MAIN_INPUT || plan.handlesVisibleAt(presentationTimeUs)) VISIBLE else HIDDEN

    private companion object {
        /** The main track is the composition's first sequence. */
        const val MAIN_INPUT = 0
        val VISIBLE: OverlaySettings = StaticOverlaySettings.Builder().build()
        val HIDDEN: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    }
}
