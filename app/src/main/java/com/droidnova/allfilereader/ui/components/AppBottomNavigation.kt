package com.droidnova.allfilereader.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.droidnova.allfilereader.navigation.AppDestination

@Composable
fun AppBottomNavigation(
    destinations: List<AppDestination>,
    isSelected: (AppDestination) -> Boolean,
    onDestinationSelected: (AppDestination) -> Unit
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
        destinations.forEach { destination ->
            val label = stringResource(destination.labelResId)
            NavigationBarItem(
                selected = isSelected(destination),
                onClick = { onDestinationSelected(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = label
                    )
                },
                label = { Text(text = label) }
            )
        }
    }
}
