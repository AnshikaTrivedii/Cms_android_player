package com.orion.player.ui.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orion.player.R
import com.orion.player.ui.theme.DarkSurfaceVariant
import com.orion.player.ui.theme.OrionPurple
import com.orion.player.ui.theme.TextPrimary
import com.orion.player.ui.theme.TextSecondary

/**
 * Back-button layer for root screens. First Back shows the overlay; second Back dismisses it.
 */
@Composable
fun BoxScope.HomeEscapeOverlayLayer() {
    var visible by remember { mutableStateOf(false) }
    var showAppGrid by remember { mutableStateOf(false) }

    BackHandler {
        if (visible) {
            visible = false
            showAppGrid = false
        } else {
            visible = true
        }
    }

    if (!visible) return

    HomeEscapeOverlay(
        showAppGrid = showAppGrid,
        onShowAppGrid = { showAppGrid = true },
        onDismiss = {
            visible = false
            showAppGrid = false
        }
    )
}

@Composable
private fun HomeEscapeOverlay(
    showAppGrid: Boolean,
    onShowAppGrid: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val settingsFocus = remember { FocusRequester() }
    val hasSystemAllApps = remember(context) { HomeEscapeLauncher.hasSystemAllApps(context) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.yield()
        runCatching { settingsFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(24.dp)
                .background(DarkSurfaceVariant, RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { }
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.home_escape_title),
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_escape_subtitle),
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { activity?.let { HomeEscapeLauncher.launchSettings(it) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(settingsFocus),
                colors = ButtonDefaults.buttonColors(containerColor = OrionPurple),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.home_escape_settings))
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    if (activity == null) return@OutlinedButton
                    if (HomeEscapeLauncher.launchAllAppsIfAvailable(activity)) return@OutlinedButton
                    onShowAppGrid()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.home_escape_all_apps))
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.home_escape_resume))
            }

            if (showAppGrid && !hasSystemAllApps) {
                Spacer(modifier = Modifier.height(20.dp))
                AppGrid(
                    onLaunch = { app ->
                        activity?.let { HomeEscapeLauncher.launchApp(it, app) }
                    }
                )
            }
        }
    }
}

@Composable
private fun AppGrid(onLaunch: (LaunchableApp) -> Unit) {
    val context = LocalContext.current
    val apps = remember(context) { HomeEscapeLauncher.queryLaunchableApps(context) }

    if (apps.isEmpty()) {
        Text(
            text = stringResource(R.string.home_escape_no_apps),
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(apps, key = { "${it.packageName}/${it.activityName}" }) { app ->
            val iconBitmap = remember(app.packageName, app.activityName) { app.icon.toImageBitmap() }
            Column(
                modifier = Modifier
                    .clickable { onLaunch(app) }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.label,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = app.label,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    if (this is BitmapDrawable && bitmap != null) {
        return bitmap.asImageBitmap()
    }
    val width = intrinsicWidth.coerceAtLeast(1)
    val height = intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}
