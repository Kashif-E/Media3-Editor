@file:OptIn(UnstableApi::class)
package com.kashif_e.media3.editor

import android.graphics.RectF
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.VideoEncoderSettings

/**
 * Defines a request for transforming a single media input.
 */
data class MediaEditRequest(
    val inputUri: Uri,
    val outputPath: String,
    val crop: CropOptions? = null,
    val resize: ResizeOptions? = null,
    val rotationDegrees: Float? = null,
    val transcode: TranscodeOptions = TranscodeOptions(),
    val removeAudio: Boolean = false,
    val removeVideo: Boolean = false,
    val flattenForSlowMotion: Boolean = false
)

/**
 * Normalized crop rectangle described in screen coordinates where (0,0) is the
 * top-left corner and (1,1) is the bottom-right corner of the input frame.
 */
data class CropOptions(val normalizedRect: RectF) {
    init {
        require(normalizedRect.left < normalizedRect.right) {
            "Crop rectangle must have positive width"
        }
        require(normalizedRect.top < normalizedRect.bottom) {
            "Crop rectangle must have positive height"
        }
        require(normalizedRect.left in 0f..1f && normalizedRect.right in 0f..1f) {
            "Crop rectangle horizontal bounds must be within [0, 1]"
        }
        require(normalizedRect.top in 0f..1f && normalizedRect.bottom in 0f..1f) {
            "Crop rectangle vertical bounds must be within [0, 1]"
        }
    }
}

/**
 * Resize instructions mapped onto Media3 [Presentation] effects.
 */
sealed interface ResizeOptions {
    data class Fixed(
        val width: Int,
        val height: Int,
        val layout: Int = Presentation.LAYOUT_SCALE_TO_FIT
    ) : ResizeOptions {
        init {
            require(width > 0) { "Width must be positive" }
            require(height > 0) { "Height must be positive" }
            require(layout in VALID_LAYOUTS) { "Unsupported presentation layout: $layout" }
        }
    }

    data class Height(val height: Int) : ResizeOptions {
        init {
            require(height > 0) { "Height must be positive" }
        }
    }

    data class AspectRatio(
        val aspectRatio: Float,
        val layout: Int = Presentation.LAYOUT_SCALE_TO_FIT
    ) : ResizeOptions {
        init {
            require(aspectRatio > 0f) { "Aspect ratio must be greater than zero" }
            require(layout in VALID_LAYOUTS) { "Unsupported presentation layout: $layout" }
        }
    }

    companion object {
        private val VALID_LAYOUTS = setOf(
            Presentation.LAYOUT_SCALE_TO_FIT,
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
            Presentation.LAYOUT_STRETCH_TO_FIT
        )
    }
}

/**
 * User friendly quality presets that map to bitrate suggestions.
 */
enum class VideoQuality { HIGH, MEDIUM, LOW }

/**
 * Encoder/transcoding knobs that control output format, bitrate, and performance.
 */
data class TranscodeOptions(
    val videoMimeType: String? = null,
    val audioMimeType: String? = null,
    val targetVideoBitrate: Int? = null,
    val videoBitrateMode: Int? = null,
    val videoQuality: VideoQuality? = null,
    val iFrameIntervalSeconds: Float? = null,
    val operatingRate: Int? = null,
    val priority: Int? = null
)

/**
 * Emits progress information derived from [Transformer][androidx.media3.transformer.Transformer].
 */
data class MediaEditProgress(
    val state: ProgressState,
    val percent: Int? = null
)

/**
 * Friendly wrapper around [Transformer.ProgressState][androidx.media3.transformer.Transformer.ProgressState].
 */
enum class ProgressState {
    NOT_STARTED,
    WAITING_FOR_AVAILABILITY,
    AVAILABLE,
    UNAVAILABLE
}

/**
 * Signals that Media3 had to relax the requested [TransformationRequest].
 */
data class FallbackEvent(
    val composition: Composition,
    val originalRequest: TransformationRequest,
    val fallbackRequest: TransformationRequest
)

/**
 * Wraps the Media3 [ExportResult] together with the chosen output location.
 */
data class MediaEditResult
    (
    val outputPath: String,
    val exportResult: ExportResult
) {
    val durationMs: Long get() = exportResult.durationMs
    val fileSizeBytes: Long get() = exportResult.fileSizeBytes
    val averageVideoBitrate: Int? get() = exportResult.averageVideoBitrate.takeIf { it != Format.NO_VALUE }
    val averageAudioBitrate: Int? get() = exportResult.averageAudioBitrate.takeIf { it != Format.NO_VALUE }
    val videoMimeType: String? get() = exportResult.videoMimeType
    val audioMimeType: String? get() = exportResult.audioMimeType
    val videoFrameCount: Int get() = exportResult.videoFrameCount
    val width: Int? get() = exportResult.width.takeIf { it != Format.NO_VALUE }
    val height: Int? get() = exportResult.height.takeIf { it != Format.NO_VALUE }
    val channelCount: Int? get() = exportResult.channelCount.takeIf { it != Format.NO_VALUE }
    val sampleRate: Int? get() = exportResult.sampleRate.takeIf { it != Format.NO_VALUE }
}

/**
 * Exception raised when a transform fails.
 */
class MediaEditException(
    message: String,
    val exportException: ExportException? = null,
    cause: Throwable? = exportException
) : Exception(message, cause)