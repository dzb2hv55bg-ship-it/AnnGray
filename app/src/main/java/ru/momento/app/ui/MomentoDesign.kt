package ru.momento.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared, dependency-free MOMENTO visual language. */
@Composable
internal fun MomentoNavigationBar(selectedIndex: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("Камера", "Комнаты", "Подписки", "Профиль")
    val icons = listOf(Icons.Default.Videocam, Icons.Default.VideoLibrary,
        Icons.Default.CollectionsBookmark, Icons.Default.PersonOutline)
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        labels.forEachIndexed { index, label ->
            NavigationBarItem(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                icon = { Icon(icons[index], contentDescription = label) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
internal fun MomentoSectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MomentoInfoCard(title: String, message: String, action: String? = null,
                            onAction: (() -> Unit)? = null) {
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (action != null && onAction != null) {
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}


/** Single source of spacing, corner radii and motion-independent UI primitives. */
internal object MomentoTokens {
    val screenPadding = 20.dp
    val cardRadius = 24.dp
    val controlRadius = 18.dp
    val sectionGap = 16.dp
    val contentGap = 8.dp
}

@Composable
internal fun MomentoPrimaryAction(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(50)
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
internal fun MomentoStatePanel(
    title: String, detail: String, actionLabel: String? = null, onAction: (() -> Unit)? = null,
    isError: Boolean = false
) {
    val palette = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(MomentoTokens.cardRadius),
        colors = CardDefaults.cardColors(containerColor = if (isError) palette.errorContainer else palette.surfaceContainerLow)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = if (isError) palette.onErrorContainer else palette.onSurface)
            Text(detail, style = MaterialTheme.typography.bodyMedium,
                color = if (isError) palette.onErrorContainer else palette.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
internal fun MomentoRoomTile(
    title: String, description: String, metadata: String, onClick: () -> Unit,
    footer: String? = null
) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MomentoTokens.cardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.VideoLibrary, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(metadata, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                if (footer != null) Text(footer, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

/** Recording progress is derived from CameraX state, not a decorative success animation. */
@Composable
internal fun MomentoRecordingStatus(isRecording: Boolean, secondsElapsed: Int) {
    val progress = (secondsElapsed.coerceIn(0, 5) / 5f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (isRecording) "● ИДЁТ ЗАПИСЬ" else "ЖИВОЙ МОМЕНТ",
                color = if (isRecording) Color(0xFFFF8C86) else Color.White,
                style = MaterialTheme.typography.labelMedium)
            Text(if (isRecording) "${secondsElapsed.coerceIn(0,5)} / 5 с" else "2–5 секунд",
                color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp),
            color = Color(0xFF81B7FF), trackColor = Color(0xFF36465E))
    }
}


/** Account-wide reminder policy; no per-room hour/count controls are exposed. */
@Composable
internal fun MomentoReminderPolicyCard(enabled: Boolean, onToggle: (Boolean) -> Unit, permissionGranted: Boolean) {
    Card(shape = RoundedCornerShape(MomentoTokens.cardRadius), modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(MomentoTokens.screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Напоминания о моменте", style = MaterialTheme.typography.titleMedium)
                    Text("5 случайных напоминаний в день, 09:00–21:00. Между ними не менее 45 минут.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = onToggle, modifier = Modifier.semantics {
                    contentDescription = "Включить или отключить напоминания"
                })
            }
            if (!permissionGranted) Text("Для доставки уведомлений разреши их в настройках Android.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun MomentoScreenHeading(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = MomentoTokens.sectionGap),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
