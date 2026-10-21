package com.meridian.tracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meridian.tracker.ui.components.FullscreenBox
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.MeridianGlobe
import com.meridian.tracker.ui.components.PillButton
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType

@Composable
fun PermissionScreen(
    onAllow: () -> Unit,
    needsSettings: Boolean,
    onOpenSettings: () -> Unit,
    approximateOnly: Boolean,
) {
    FullscreenBox {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Text("MERIDIAN", style = MType.Wordmark)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                MeridianGlobe(Modifier.size(240.dp))
            }
            Text("Your routes.\nYour data.", style = MType.Title.copy(fontSize = MType.Title.fontSize * 1.25f, lineHeight = MType.Title.lineHeight * 1.2f))
            Spacer(Modifier.height(14.dp))
            Text(
                "Meridian records runs, rides and walks entirely on this phone. No account, no feed, no one watching.",
                style = MType.Body,
            )
            Spacer(Modifier.height(24.dp))
            Bullet("GPS keeps recording with the screen off")
            Bullet("Works offline, even in airplane mode")
            Bullet("Activities never leave your phone")
            Spacer(Modifier.height(28.dp))

            val explanation = when {
                approximateOnly -> "Meridian needs Precise location to measure distance. Turn it on in Settings."
                needsSettings -> "Location was turned off for Meridian. You can allow it in Settings."
                else -> "Location draws your route. The notification shows live stats while your screen is off."
            }
            Text(explanation, style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.9f))
            Spacer(Modifier.height(16.dp))
            if (needsSettings || approximateOnly) {
                PillButton("Open Settings", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
            } else {
                PillButton("Allow location", onClick = onAllow, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(
        Modifier.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(MIcons.Check, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MType.Body.copy(color = MColors.Text))
    }
}
