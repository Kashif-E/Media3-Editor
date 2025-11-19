package com.kashif_e.media3.editor

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * High-level wrapper around Media3 Transformer that provides ergonomic video editing primitives.
 */
@OptIn(markerClass = [UnstableApi::class])
class MediaEditManager(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val transformerThread = HandlerThread("MediaEditManager").apply { start() }
    private val transformerHandler = Handler(transformerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun execute(
        request: MediaEditRequest,
        onProgress: (MediaEditProgress) -> Unit = {},
        onFallback: (FallbackEvent) -> Unit = {}
    ): MediaEditResult = suspendCancellableCoroutine { continuation ->
    val progressHolder = ProgressHolder()
        var released = false
        lateinit var transformer: Transformer
        lateinit var progressRunnable: Runnable

        fun releaseIfNeeded() {
            if (released) return
            released = true
            transformerHandler.removeCallbacks(progressRunnable)
        }

        progressRunnable = Runnable {
            if (!continuation.isActive || released) return@Runnable
            val state = transformer.getProgress(progressHolder)
            val progress = mapProgress(state, progressHolder.progress)
            mainHandler.post { onProgress(progress) }
            transformerHandler.postDelayed(progressRunnable, PROGRESS_UPDATE_INTERVAL_MS)
        }

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult) {
                releaseIfNeeded()
                if (continuation.isActive) {
                    continuation.resume(MediaEditResult(request.outputPath, exportResult))
                }
            }

            override fun onError(
                composition: androidx.media3.transformer.Composition,
                exportResult: androidx.media3.transformer.ExportResult,
                exportException: ExportException
            ) {
                releaseIfNeeded()
                if (continuation.isActive) {
                    val message = exportException.message ?: "Media transform failed"
                    continuation.resumeWithException(MediaEditException(message, exportException))
                }
            }

            override fun onFallbackApplied(
                composition: androidx.media3.transformer.Composition,
                originalTransformationRequest: androidx.media3.transformer.TransformationRequest,
                fallbackTransformationRequest: androidx.media3.transformer.TransformationRequest
            ) {
                mainHandler.post {
                    onFallback(
                        FallbackEvent(
                            composition = composition,
                            originalRequest = originalTransformationRequest,
                            fallbackRequest = fallbackTransformationRequest
                        )
                    )
                }
            }
        }

        transformer = buildTransformer(request, listener)
        val editedMediaItem = buildEditedMediaItem(request)

        continuation.invokeOnCancellation {
            transformerHandler.post {
                try {
                    transformer.cancel()
                } finally {
                    releaseIfNeeded()
                }
            }
        }

        mainHandler.post { onProgress(MediaEditProgress(ProgressState.NOT_STARTED, null)) }

        transformerHandler.post {
            try {
                transformer.start(editedMediaItem, request.outputPath)
                transformerHandler.post(progressRunnable)
            } catch (throwable: Throwable) {
                releaseIfNeeded()
                if (continuation.isActive) {
                    continuation.resumeWithException(MediaEditException("Unable to start transformation", cause = throwable))
                }
            }
        }
    }

    private fun buildTransformer(
        request: MediaEditRequest,
        listener: Transformer.Listener
    ): Transformer {
        val builder = Transformer.Builder(appContext)
            .setLooper(transformerThread.looper)
            .addListener(listener)

        request.transcode.videoMimeType?.let(builder::setVideoMimeType)
        request.transcode.audioMimeType?.let(builder::setAudioMimeType)

        buildEncoderFactory(request.transcode)?.let(builder::setEncoderFactory)

        return builder.build()
    }

    private fun buildEncoderFactory(options: TranscodeOptions): Codec.EncoderFactory? {
        val videoSettings = buildVideoEncoderSettings(options) ?: return null
        return DefaultEncoderFactory.Builder(appContext)
            .setRequestedVideoEncoderSettings(videoSettings)
            .build()
    }

    private fun buildVideoEncoderSettings(options: TranscodeOptions): VideoEncoderSettings? {
        var changed = false
        val builder = VideoEncoderSettings.Builder()

        val requestedBitrate = options.targetVideoBitrate ?: options.videoQuality?.let { qualityToBitrate(it) }
        if (requestedBitrate != null) {
            builder.setBitrate(requestedBitrate)
            changed = true
        }

        options.videoBitrateMode?.let {
            builder.setBitrateMode(it)
            changed = true
        }

        options.iFrameIntervalSeconds?.let {
            builder.setiFrameIntervalSeconds(it)
            changed = true
        }

        if (options.operatingRate != null && options.priority != null) {
            builder.setEncoderPerformanceParameters(options.operatingRate, options.priority)
            changed = true
        }

        return if (changed) builder.build() else null
    }

    private fun buildEditedMediaItem(request: MediaEditRequest): EditedMediaItem {
        val mediaItem = MediaItem.fromUri(request.inputUri)
        val videoEffects = buildVideoEffects(request)

        val builder = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(request.removeAudio)
            .setRemoveVideo(request.removeVideo)

        if (request.flattenForSlowMotion) {
            builder.setFlattenForSlowMotion(true)
        }

        if (videoEffects.isNotEmpty()) {
            builder.setEffects(Effects(emptyList(), videoEffects))
        }

        return builder.build()
    }

    private fun buildVideoEffects(request: MediaEditRequest): List<Effect> {
        val effects = mutableListOf<Effect>()
        request.crop?.toCropEffect()?.let(effects::add)
        request.resize?.toPresentation()?.let(effects::add)
        request.rotationDegrees?.takeIf { it % 360f != 0f }?.let { rotation ->
            effects.add(
                ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees(rotation)
                    .build()
            )
        }
        return effects
    }

    private fun CropOptions.toCropEffect(): Crop {
        val left = normalizedRect.left * 2f - 1f
        val right = normalizedRect.right * 2f - 1f
        val top = 1f - normalizedRect.top * 2f
        val bottom = 1f - normalizedRect.bottom * 2f
        return Crop(left, right, bottom, top)
    }

    private fun ResizeOptions.toPresentation(): Presentation = when (this) {
        is ResizeOptions.Fixed -> Presentation.createForWidthAndHeight(width, height, layout)
        is ResizeOptions.Height -> Presentation.createForHeight(height)
        is ResizeOptions.AspectRatio -> Presentation.createForAspectRatio(aspectRatio, layout)
    }

    private fun mapProgress(state: @Transformer.ProgressState Int, percent: Int): MediaEditProgress {
        val progressState = when (state) {
            Transformer.PROGRESS_STATE_NOT_STARTED -> ProgressState.NOT_STARTED
            Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY -> ProgressState.WAITING_FOR_AVAILABILITY
            Transformer.PROGRESS_STATE_AVAILABLE -> ProgressState.AVAILABLE
            Transformer.PROGRESS_STATE_UNAVAILABLE -> ProgressState.UNAVAILABLE
            else -> ProgressState.UNAVAILABLE
        }
        val progressPercent = if (state == Transformer.PROGRESS_STATE_AVAILABLE) percent else null
        return MediaEditProgress(progressState, progressPercent)
    }

    private fun qualityToBitrate(quality: VideoQuality): Int = when (quality) {
        VideoQuality.HIGH -> 12_000_000
        VideoQuality.MEDIUM -> 6_000_000
        VideoQuality.LOW -> 3_000_000
    }

    override fun close() {
        transformerThread.quitSafely()
        transformerThread.join(THREAD_JOIN_TIMEOUT_MS)
    }

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL_MS = 250L
        private const val THREAD_JOIN_TIMEOUT_MS = 1_000L
    }
}
