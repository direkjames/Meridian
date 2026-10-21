package com.meridian.tracker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType

enum class Tab(val label: String) {
    RECORD("Record"),
    ACTIVITIES("Activities"),
    PLAN("Plan"),
    PROGRESS("Progress"),
}

@Composable
fun MeridianNavBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MColors.Surface)
    ) {
        HorizontalHairline()
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
        ) {
            Tab.entries.forEach { tab ->
                NavItem(
                    label = tab.label,
                    icon = when (tab) {
                        Tab.RECORD -> MIcons.Record
                        Tab.ACTIVITIES -> MIcons.Pulse
                        Tab.PLAN -> MIcons.Calendar
                        Tab.PROGRESS -> MIcons.Chart
                    },
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(if (selected) MColors.Accent else MColors.Muted, label = "navTint")
    val textColor by animateColorAsState(if (selected) MColors.Text else MColors.Muted, label = "navText")
    val indicator by animateFloatAsState(if (selected) 1f else 0f, label = "navIndicator")
    Box(
        modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(28.dp)
                .height(3.dp)
                .alpha(indicator)
                .clip(CircleShape)
                .background(MColors.Accent)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(label.uppercase(), style = MType.Label.copy(color = textColor, fontSize = MType.Label.fontSize * 0.92f))
        }
    }
}
