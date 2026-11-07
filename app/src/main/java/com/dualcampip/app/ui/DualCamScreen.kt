package com.dualcampip.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dualcampip.app.DualCamViewModel
import com.dualcampip.app.R
import com.dualcampip.app.ui.theme.Ink
import com.dualcampip.app.ui.theme.Line
import com.dualcampip.app.ui.theme.Muted
import com.dualcampip.app.ui.theme.Paper
import com.dualcampip.app.ui.theme.RecordRed
import com.dualcampip.app.ui.theme.SavedFg
import com.dualcampip.app.ui.theme.WarningBg
import com.dualcampip.app.ui.theme.WarningFg
import kotlinx.coroutines.delay

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
) {
    val probing = state.phase == Phase.Probing || state.permission == PermissionState.Unknown
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        if (state.permission == PermissionState.Granted) {
            PreviewSurface(
                modifier = Modifier.fillMaxSize(),
                onAvailable = onSurfaceAvailable,
                onDestroyed = onSurfaceDestroyed,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                    ),
                )
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp),
        ) {
            Text(text = stringResource(R.string.title), style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
            Text(text = stringResource(R.string.subtitle), color = Muted)
            state.backendLabel?.let {
                Spacer(Modifier.height(6.dp))
                Text(text = it, color = Muted)
            }
            state.unsupportedMessage?.let { message ->
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(WarningBg)
                        .padding(14.dp),
                ) {
                    Text(text = stringResource(R.string.unsupported_title), color = WarningFg)
                    Spacer(Modifier.height(4.dp))
                    Text(text = message, color = WarningFg)
                }
            }
            state.statusMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, color = Paper)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.support_note),
                color = Muted,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
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
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    ),
                )
                .navigationBarsPadding()
                .padding(top = 36.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RecordingMessage(state.recording)
            Controls(
                state = state,
                onRecord = onRecord,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun RecordingMessage(recording: RecordingUi) {
    val text = when (recording) {
        is RecordingUi.Active -> stringResource(R.string.recording_label) + "  " + elapsedLabel(recording)
        RecordingUi.Saving -> stringResource(R.string.saving)
        is RecordingUi.Saved -> stringResource(R.string.saved, recording.displayName)
        is RecordingUi.Failed -> recording.message
        RecordingUi.Idle -> null
    }
    if (text != null) {
        val color = if (recording is RecordingUi.Saved) SavedFg else Paper
        Text(text = text, color = color, modifier = Modifier.padding(bottom = 10.dp))
    }
}

@Composable
private fun elapsedLabel(active: RecordingUi.Active): String {
    var now by remember(active.startedAtElapsedMs) { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(active.startedAtElapsedMs) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    val seconds = ((now - active.startedAtElapsedMs) / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}

@Composable
private fun Controls(
    state: DualCamUiState,
    onRecord: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val recording = state.recording is RecordingUi.Active
    val saving = state.recording is RecordingUi.Saving
    val recordEnabled = recording || (state.canRecord && !saving)
    val recordDescription = stringResource(if (recording) R.string.record_stop else R.string.record_start)
    Box(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = onOpenSettings,
            enabled = !recording && !saving,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp),
        ) {
            Text(stringResource(R.string.settings), color = if (recording || saving) Muted else Paper)
        }
        Box(
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .border(3.dp, if (recordEnabled) Paper else Line, CircleShape)
                .clickable(enabled = recordEnabled, onClick = onRecord)
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
    }
}
