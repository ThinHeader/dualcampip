package com.dualcampip.app.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun PreviewSurface(
    modifier: Modifier = Modifier,
    onAvailable: (Surface, Int, Int, Int) -> Unit,
    onDestroyed: () -> Unit,
) {
    val available = rememberUpdatedState(onAvailable)
    val destroyed = rememberUpdatedState(onDestroyed)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            val rotation = display?.rotation ?: Surface.ROTATION_0
                            available.value(holder.surface, width, height, rotation)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            destroyed.value()
                        }
                    },
                )
            }
        },
    )
}
