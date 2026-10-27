package com.mosu.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * An entry of the settings navigation rail.
 */
sealed interface SettingsRailEntry {

    /** A small label separating groups of sections (e.g. "Multiplayer" and "Game"). */
    data class Header(val text: String) : SettingsRailEntry

    data class Item<T>(val key: T, val label: String, @DrawableRes val icon: Int) : SettingsRailEntry
}

/**
 * Material 3 navigation rail used to switch between settings sections.
 */
@Composable
fun <T> SettingsRail(
    entries: List<SettingsRailEntry>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    NavigationRail(containerColor = Color.Transparent) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        ) {
            for (entry in entries) {
                when (entry) {
                    is SettingsRailEntry.Header -> Text(
                        text = entry.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )

                    is SettingsRailEntry.Item<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val item = entry as SettingsRailEntry.Item<T>

                        NavigationRailItem(
                            selected = item.key == selected,
                            onClick = { onSelect(item.key) },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(item.label) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        }
    }
}
