package com.orion.player.ui.pairing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.orion.player.BuildConfig

/**
 * Pairing screen displayed on first launch.
 * Shows a large pairing code for the CMS user to enter.
 */
@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is PairingUiState.Paired) {
            onPaired()
        }
    }

    if (uiState is PairingUiState.Paired) {
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1A1A2E),
                        Color(0xFF0F0F1A)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        when (val state = uiState) {
            is PairingUiState.Loading -> LoadingState()
            is PairingUiState.ShowCode -> PairingCodeDisplay(code = state.pairingCode)
            is PairingUiState.Error -> ErrorState(
                message = state.message,
                onRetry = { viewModel.retry() }
            )
            is PairingUiState.Paired -> { /* Handled above */ }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = Color(0xFF6C63FF),
            strokeWidth = 3.dp,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Initializing device...",
            color = Color(0xFFB0B0C0),
            fontSize = 16.sp
        )
    }
}

@Composable
private fun PairingCodeDisplay(code: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        // Orion branding
        Icon(
            imageVector = Icons.Default.Tv,
            contentDescription = "Display",
            tint = Color(0xFF6C63FF),
            modifier = Modifier.size(56.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "ORION",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 8.sp
        )

        Text(
            text = "DIGITAL SIGNAGE",
            color = Color(0xFF6C63FF),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 4.sp
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Instruction
        Text(
            text = "Go to your Orion CMS dashboard,\nclick Add Device, and enter this code:",
            color = Color(0xFFB0B0C0),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Pairing code boxes
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            code.forEach { char ->
                PairingCodeChar(char = char)
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Waiting indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.alpha(pulseAlpha)
        ) {
            CircularProgressIndicator(
                color = Color(0xFF6C63FF),
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Waiting for pairing...",
                color = Color(0xFF6C63FF),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun PairingCodeChar(char: Char) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(64.dp)
            .background(
                Color(0xFF1E1E3A),
                RoundedCornerShape(12.dp)
            )
            .border(
                width = 1.5.dp,
                color = Color(0xFF6C63FF).copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Text(
            text = char.toString(),
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "⚠️",
            fontSize = 48.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Connection Error",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            color = Color(0xFFB0B0C0),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 48.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "App version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = Color(0xFF6C63FF),
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF6C63FF)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Retry"
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Retry")
        }
    }
}
