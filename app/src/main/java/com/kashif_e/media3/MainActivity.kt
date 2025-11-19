package com.kashif_e.media3

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.MediaItem as ExoMediaItem
import com.google.android.exoplayer2.ui.PlayerView
import com.kashif_e.media3.editor.CropOptions
import com.kashif_e.media3.editor.MediaEditManager
import com.kashif_e.media3.editor.MediaEditProgress
import com.kashif_e.media3.editor.MediaEditRequest
import com.kashif_e.media3.editor.ProgressState
import com.kashif_e.media3.editor.ResizeOptions
import com.kashif_e.media3.editor.TranscodeOptions
import com.kashif_e.media3.editor.VideoQuality
import com.kashif_e.media3.ui.theme.Media3Theme
import kotlinx.coroutines.launch
import java.io.File

private enum class EditPreset { NONE, SQUARE, STORY_9_16, LANDSCAPE_16_9 }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Media3Theme {
                MinimalEditorRoot()
            }
        }
    }
}

@Composable
private fun MinimalEditorRoot() {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        MinimalEditorScreen(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            onError = { message ->
                scope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinimalEditorScreen(
    modifier: Modifier = Modifier,
    onError: (String) -> Unit
) {
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var lastOutputPath by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<MediaEditProgress?>(null) }
    var quality by remember { mutableStateOf(VideoQuality.MEDIUM) }
    var targetWidthText by remember { mutableStateOf(TextFieldValue("1280")) }
    var targetHeightText by remember { mutableStateOf(TextFieldValue("720")) }
    var rotationText by remember { mutableStateOf(TextFieldValue("0")) }
    var removeAudio by remember { mutableStateOf(false) }
    var removeVideo by remember { mutableStateOf(false) }
    var flattenSlowMotion by remember { mutableStateOf(true) }

    // Simple crop mode: full frame vs square center.
    var useSquareCrop by remember { mutableStateOf(false) }

    // Advanced encoder knobs
    var targetBitrateText by remember { mutableStateOf(TextFieldValue("")) }
    var iFrameIntervalText by remember { mutableStateOf(TextFieldValue("")) }
    var operatingRateText by remember { mutableStateOf(TextFieldValue("")) }
    var priorityText by remember { mutableStateOf(TextFieldValue("")) }

    // Simple preset system: NONE, Square (1:1), Portrait Story (9:16), Landscape (16:9)
    var preset by remember { mutableStateOf(EditPreset.NONE) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        inputUri = uri
        lastOutputPath = null
        progress = null
    }

    // On Android < Q we still rely on WRITE_EXTERNAL_STORAGE for gallery saves.
    val legacyWritePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            onError("Storage permission denied; cannot save to gallery")
        }
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Media3 editor",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Pick a clip, choose a preset, then fine‑tune size, look, and encoder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Source
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Source", style = MaterialTheme.typography.titleMedium)

                OutlinedButton(onClick = { pickVideoLauncher.launch("video/*") }) {
                    Text(if (inputUri == null) "Pick video" else "Change video")
                }

                if (inputUri != null) {
                    Text(
                        text = "Input: $inputUri",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "No video selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Size, presets, rotation
        ElevatedCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Size & presets", style = MaterialTheme.typography.titleMedium)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = targetWidthText,
                        onValueChange = { targetWidthText = it },
                        label = { Text("Width") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = targetHeightText,
                        onValueChange = { targetHeightText = it },
                        label = { Text("Height") },
                        singleLine = true
                    )
                }

                Text(
                    text = "Presets",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        EditPreset.NONE to "Custom",
                        EditPreset.SQUARE to "Square 1:1",
                        EditPreset.STORY_9_16 to "Story 9:16",
                        EditPreset.LANDSCAPE_16_9 to "Landscape 16:9"
                    ).forEach { (p, label) ->
                        OutlinedButton(
                            onClick = {
                                preset = p
                                when (p) {
                                    EditPreset.NONE -> Unit
                                    EditPreset.SQUARE -> {
                                        targetWidthText = TextFieldValue("1080")
                                        targetHeightText = TextFieldValue("1080")
                                        rotationText = TextFieldValue("0")
                                        useSquareCrop = true
                                        quality = VideoQuality.HIGH
                                    }
                                    EditPreset.STORY_9_16 -> {
                                        targetWidthText = TextFieldValue("1080")
                                        targetHeightText = TextFieldValue("1920")
                                        rotationText = TextFieldValue("0")
                                        useSquareCrop = false
                                        quality = VideoQuality.MEDIUM
                                    }
                                    EditPreset.LANDSCAPE_16_9 -> {
                                        targetWidthText = TextFieldValue("1920")
                                        targetHeightText = TextFieldValue("1080")
                                        rotationText = TextFieldValue("0")
                                        useSquareCrop = false
                                        quality = VideoQuality.HIGH
                                    }
                                }
                            },
                            enabled = preset != p
                        ) {
                            Text(label)
                        }
                    }
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = rotationText,
                    onValueChange = { rotationText = it },
                    label = { Text("Rotation (°)") },
                    supportingText = { Text("Use 90 / 270 to flip between portrait and landscape.") },
                    singleLine = true
                )
            }
        }

        // Look & sound
        ElevatedCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Look & sound", style = MaterialTheme.typography.titleMedium)

                Text("Quality", style = MaterialTheme.typography.labelLarge)
                RowOfQualityButtons(current = quality, onChange = { quality = it })

                Divider()

                Text("Crop", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useSquareCrop, onCheckedChange = { useSquareCrop = it })
                    Text("Square center crop")
                }

                Divider()

                Text("Audio / video", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeAudio, onCheckedChange = { removeAudio = it })
                    Text("Remove audio (mute)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeVideo, onCheckedChange = { removeVideo = it })
                    Text("Remove video (audio only)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = flattenSlowMotion, onCheckedChange = { flattenSlowMotion = it })
                    Text("Flatten slow-motion")
                }
            }
        }

        // Encoder advanced
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Encoder (advanced)", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Optional tuning for bitrate, keyframe interval, and performance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = targetBitrateText,
                    onValueChange = { targetBitrateText = it },
                    label = { Text("Target video bitrate (bps)") },
                    supportingText = { Text("Leave empty to use the quality preset.") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = iFrameIntervalText,
                    onValueChange = { iFrameIntervalText = it },
                    label = { Text("I-frame interval (seconds)") },
                    supportingText = { Text("2.0–5.0 is typical; lower = more keyframes.") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = operatingRateText,
                    onValueChange = { operatingRateText = it },
                    label = { Text("Operating rate (fps)") },
                    supportingText = { Text("Optional; higher values favor speed over efficiency.") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = priorityText,
                    onValueChange = { priorityText = it },
                    label = { Text("Encoder priority") },
                    supportingText = { Text("Optional integer; higher means higher priority.") },
                    singleLine = true
                )
            }
        }

        // Run & status
        Button(
            enabled = inputUri != null && !isRunning,
            onClick = {
                val uri = inputUri
                if (uri == null) {
                    onError("Please pick a video first")
                    return@Button
                }

                // On Android versions prior to Q we need WRITE_EXTERNAL_STORAGE to publish to gallery.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasPermission) {
                        legacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        // We'll ask the user to tap again after granting.
                        return@Button
                    }
                }

                val width = targetWidthText.text.toIntOrNull()?.coerceAtLeast(16) ?: 1280
                val height = targetHeightText.text.toIntOrNull()?.coerceAtLeast(16) ?: 720

                val rotationDegrees = rotationText.text.toFloatOrNull() ?: 0f

                val outputFile = File(
                    context.cacheDir,
                    "edit-${System.currentTimeMillis()}.mp4"
                )

                val cropRect = if (useSquareCrop) {
                    // 1:1 square centered inside the frame (normalized coordinates)
                    val side = 1f
                    val left = 0.5f - side / 2f
                    val top = 0.5f - side / 2f
                    android.graphics.RectF(left, top, left + side, top + side)
                } else {
                    android.graphics.RectF(0f, 0f, 1f, 1f)
                }

                val targetBitrate = targetBitrateText.text.toIntOrNull()
                val iFrameIntervalSeconds = iFrameIntervalText.text.toFloatOrNull()
                val operatingRate = operatingRateText.text.toIntOrNull()
                val priority = priorityText.text.toIntOrNull()

                val request = MediaEditRequest(
                    inputUri = uri,
                    outputPath = outputFile.absolutePath,
                    crop = CropOptions(normalizedRect = cropRect),
                    resize = ResizeOptions.Fixed(
                        width = width,
                        height = height
                    ),
                    rotationDegrees = rotationDegrees,
                    flattenForSlowMotion = flattenSlowMotion,
                    removeAudio = removeAudio,
                    removeVideo = removeVideo,
                    transcode = TranscodeOptions(
                        videoMimeType = "video/avc",
                        audioMimeType = "audio/mp4a-latm",
                        targetVideoBitrate = targetBitrate,
                        videoQuality = quality,
                        iFrameIntervalSeconds = iFrameIntervalSeconds,
                        operatingRate = operatingRate,
                        priority = priority
                    )
                )

                isRunning = true
                progress = null
                lastOutputPath = null

                scope.launch {
                    val manager = MediaEditManager(context)
                    try {
                        val result = manager.execute(
                            request = request,
                            onProgress = { progress = it }
                        )
                        lastOutputPath = result.outputPath

                        // After export completes, copy the file into MediaStore so it appears in the gallery.
                        try {
                            val galleryUri = saveVideoToGallery(context, File(result.outputPath))
                            lastOutputPath = galleryUri?.toString() ?: result.outputPath
                        } catch (t: Throwable) {
                            // Non-fatal: keep local cache path and surface a soft error.
                            onError("Saved to app cache only: ${t.message ?: "unknown error"}")
                        }
                    } catch (t: Throwable) {
                        onError(t.message ?: "Transform failed")
                    } finally {
                        manager.close()
                        isRunning = false
                    }
                }
            }
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .height(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text("Run edit")
            }
        }

        Spacer(Modifier.height(16.dp))

        progress?.let { p ->
            val label = when (p.state) {
                ProgressState.NOT_STARTED -> "Not started"
                ProgressState.WAITING_FOR_AVAILABILITY -> "Waiting for progress"
                ProgressState.AVAILABLE -> "In progress"
                ProgressState.UNAVAILABLE -> "Unavailable"
            }
            val percentText = p.percent?.let { ": ${it}%" } ?: ""
            Text("Progress: $label $percentText", style = MaterialTheme.typography.bodyMedium)
        }

        lastOutputPath?.let { path ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Output saved to: $path",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))

            VideoPreview(uriString = path)
        }
    }
}

@Composable
private fun VideoPreview(uriString: String) {
    val context = LocalContext.current
    val uri = try {
        android.net.Uri.parse(uriString)
    } catch (_: Throwable) {
        null
    }

    if (uri == null) {
        Text("Cannot preview: invalid Uri")
        return
    }

    val player = remember {
        SimpleExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    )

    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }
}

/**
 * Inserts the transformed video into MediaStore so it appears in the user gallery.
 *
 * Returns the [Uri] of the inserted item, or null if insertion failed.
 */
private fun saveVideoToGallery(context: android.content.Context, sourceFile: File): Uri? {
    val resolver = context.contentResolver
    val fileName = sourceFile.nameWithoutExtension.ifBlank {
        "media3-edit-${System.currentTimeMillis()}"
    } + ".mp4"

    val collectionUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        // For older devices we still write to the primary external volume.
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }

    val itemUri = resolver.insert(collectionUri, values) ?: return null

    resolver.openOutputStream(itemUri)?.use { out ->
        sourceFile.inputStream().use { input ->
            input.copyTo(out)
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val finishedValues = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        resolver.update(itemUri, finishedValues, null, null)
    }

    return itemUri
}

@Composable
private fun RowOfQualityButtons(
    current: VideoQuality,
    onChange: (VideoQuality) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(VideoQuality.LOW, VideoQuality.MEDIUM, VideoQuality.HIGH).forEach { q ->
            OutlinedButton(
                onClick = { onChange(q) },
                enabled = current != q
            ) {
                Text(q.name)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MinimalEditorPreview() {
    Media3Theme {
        MinimalEditorScreen(onError = {})
    }
}