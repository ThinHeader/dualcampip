package com.dualcampip.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dualcampip.app.DualCamViewModel
import com.dualcampip.app.R
import com.dualcampip.app.encode.EncodeConfig
import com.dualcampip.app.model.PipConfig
import com.dualcampip.app.render.clampPipTopLeft
import com.dualcampip.app.render.coverFrame
import com.dualcampip.app.render.pipRect
import com.dualcampip.app.render.visibleFrameBounds
import com.dualcampip.app.ui.theme.Ink
import com.dualcampip.app.ui.theme.Line
import com.dualcampip.app.ui.theme.Muted
import com.dualcampip.app.ui.theme.Paper
import com.dualcampip.app.ui.theme.RecordRed

@Composable
fun DualCamApp(viewModel: DualCamViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(lifecycleOwner) {
        viewModel.attachLifecycle(lifecycleOwner)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.onPermissions(
            camera = result[Manifest.permission.CAMERA] == true,
            audio = result[Manifest.permission.RECORD_AUDIO] == true,
        )
    }

    fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
        )
    }

    LaunchedEffect(Unit) {
        val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (camera && audio) viewModel.onPermissions(camera = true, audio = true)
        else requestPermissions()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (camera) viewModel.onPermissions(camera, audio)
                    viewModel.onHostStart()
                }
                Lifecycle.Event.ON_STOP -> viewModel.onHostStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val recording = state.recording is RecordingUi.Active
    BackHandler(enabled = recording) { viewModel.stopRecording() }
    LaunchedEffect(state.unsupportedMessage) {
        val message = state.unsupportedMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(state.statusMessage) {
        val message = state.statusMessage ?: return@LaunchedEffect
        if (message == context.getString(R.string.audio_failed)) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(state.recording) {
        when (val rec = state.recording) {
            is RecordingUi.Failed -> Toast.makeText(context, rec.message, Toast.LENGTH_LONG).show()
            is RecordingUi.Saved -> Toast.makeText(
                context,
                context.getString(R.string.saved, rec.displayName),
                Toast.LENGTH_LONG,
            ).show()
            else -> Unit
        }
    }
    LaunchedEffect(recording) {
        if (recording) showSettings = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        if (state.permission == PermissionState.Denied) {
            PermissionPane(onRequest = { requestPermissions() })
        } else {
            CameraPane(
                state = state,
                onSurfaceAvailable = viewModel::onSurfaceAvailable,
                onSurfaceDestroyed = viewModel::onSurfaceDestroyed,
                onRecord = {
                    if (state.recording is RecordingUi.Active) viewModel.stopRecording()
                    else viewModel.startRecording()
                },
                onOpenSettings = { showSettings = true },
                onMovePip = viewModel::updatePip,
            )
        }
        if (showSettings && state.permission == PermissionState.Granted) {
            PipSettingsSheet(
                config = state.pip,
                locked = state.recording is RecordingUi.Active || state.recording is RecordingUi.Saving,
                onDismiss = { showSettings = false },
                onChange = viewModel::updatePip,
            )
        }
    }
}

@Composable
private fun PermissionPane(onRequest: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = stringResource(R.string.permission_title), style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(text = stringResource(R.string.permission_body), color = Muted)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onRequest) {
            Text(stringResource(R.string.permission_grant), color = Paper)
        }
        TextButton(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
                context.startActivity(intent)
            },
        ) {
            Text(stringResource(R.string.permission_settings), color = Paper)
        }
    }
}

@Composable
private fun CameraPane(
    state: DualCamUiState,
    onSurfaceAvailable: (android.view.Surface, Int, Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onRecord: () -> Unit,
    onOpenSettings: () -> Unit,
    onMovePip: (PipConfig) -> Unit,
) {
    val probing = state.phase == Phase.Probing || state.permission == PermissionState.Unknown
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .onSizeChanged { viewSize = it },
    ) {
        if (state.permission == PermissionState.Granted) {
            PreviewSurface(
                modifier = Modifier.fillMaxSize(),
                onAvailable = onSurfaceAvailable,
                onDestroyed = onSurfaceDestroyed,
            )
        }
        if (probing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
                color = Paper,
                strokeWidth = 2.dp,
            )
        }
        RecordButton(
            state = state,
            onRecord = onRecord,
            onOpenSettings = onOpenSettings,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
        )
        if (state.permission == PermissionState.Granted && viewSize.width > 0 && viewSize.height > 0) {
            PipDragTarget(
                pip = state.pip,
                frontAspect = state.frontAspect,
                viewWidth = viewSize.width.toFloat(),
                viewHeight = viewSize.height.toFloat(),
                enabled = state.recording !is RecordingUi.Active && state.recording !is RecordingUi.Saving,
                onMove = onMovePip,
            )
        }
    }
}

@Composable
private fun PipDragTarget(
    pip: PipConfig,
    frontAspect: Float,
    viewWidth: Float,
    viewHeight: Float,
    enabled: Boolean,
    onMove: (PipConfig) -> Unit,
) {
    val density = LocalDensity.current
    val frame = coverFrame(viewWidth, viewHeight, EncodeConfig.ASPECT_W_OVER_H)
    val local = pipRect(
        frameWidth = frame.width,
        frameHeight = frame.height,
        contentAspect = frontAspect,
        corner = pip.corner,
        widthFraction = pip.widthFraction,
        leftFraction = pip.leftFraction,
        topFraction = pip.topFraction,
    )
    val visible = visibleFrameBounds(frame, viewWidth, viewHeight)
    val margin = PipConfig.MARGIN_FRACTION * frame.width
    val description = stringResource(R.string.pip_drag_cd)
    val latestPip = rememberUpdatedState(pip)
    val latestAspect = rememberUpdatedState(frontAspect)
    Box(
        modifier = Modifier
            .offsetPx(local.left + frame.left, local.top + frame.top, density)
            .sizePx(local.width, local.height, density)
            .semantics { contentDescription = description }
            .pointerInput(enabled, viewWidth, viewHeight) {
                if (!enabled) return@pointerInput
                var left = 0f
                var top = 0f
                var pipWidth = 0f
                var pipHeight = 0f
                detectDragGestures(
                    onDragStart = {
                        val current = latestPip.value
                        val placed = pipRect(
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                            contentAspect = latestAspect.value,
                            corner = current.corner,
                            widthFraction = current.widthFraction,
                            leftFraction = current.leftFraction,
                            topFraction = current.topFraction,
                        )
                        left = placed.left
                        top = placed.top
                        pipWidth = placed.width
                        pipHeight = placed.height
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        val (clampedLeft, clampedTop) = clampPipTopLeft(
                            left = left + drag.x,
                            top = top + drag.y,
                            pipWidth = pipWidth,
                            pipHeight = pipHeight,
                            bounds = visible,
                            margin = margin,
                        )
                        left = clampedLeft
                        top = clampedTop
                        onMove(
                            latestPip.value.copy(
                                leftFraction = clampedLeft / frame.width,
                                topFraction = clampedTop / frame.height,
                            ),
                        )
                    },
                )
            },
    )
}

private fun Modifier.offsetPx(x: Float, y: Float, density: Density): Modifier {
    val xDp = with(density) { x.toDp() }
    val yDp = with(density) { y.toDp() }
    return offset(x = xDp, y = yDp)
}

private fun Modifier.sizePx(width: Float, height: Float, density: Density): Modifier {
    val widthDp = with(density) { width.toDp() }
    val heightDp = with(density) { height.toDp() }
    return size(width = widthDp, height = heightDp)
}

@Composable
private fun RecordButton(
    state: DualCamUiState,
    onRecord: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = state.recording is RecordingUi.Active
    val saving = state.recording is RecordingUi.Saving
    val recordEnabled = recording || (state.canRecord && !saving)
    val recordDescription = stringResource(if (recording) R.string.record_stop else R.string.record_start)
    Box(
        modifier = modifier
            .size(78.dp)
            .clip(CircleShape)
            .border(3.dp, if (recordEnabled) Paper else Line, CircleShape)
            .combinedClickable(
                enabled = recordEnabled || (!recording && !saving),
                onClick = onRecord,
                onLongClick = { if (!recording && !saving) onOpenSettings() },
            )
            .semantics {
                role = Role.Button
                contentDescription = recordDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            saving -> CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Paper,
                strokeWidth = 2.dp,
            )
            recording -> Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(RecordRed),
            )
            else -> Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(if (recordEnabled) RecordRed else RecordRed.copy(alpha = 0.28f)),
            )
        }
    }
}
