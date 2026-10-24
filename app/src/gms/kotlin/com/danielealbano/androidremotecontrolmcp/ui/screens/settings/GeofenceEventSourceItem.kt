package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.danielealbano.androidremotecontrolmcp.ui.navigation.GeofenceRoutes
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.GeofenceSettingsViewModel

/** gms flavor: the "Geofence Events" row in the Event Channel settings list. */
fun LazyListScope.geofenceEventSourceItem(navController: NavHostController) {
    item {
        val viewModel: GeofenceSettingsViewModel = hiltViewModel()
        val config by viewModel.geofenceConfig.collectAsStateWithLifecycle()
        ListItem(
            headlineContent = { Text("Geofence Events") },
            leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { viewModel.updateGeofenceChannelEnabled(it) },
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            },
            modifier = Modifier.clickable { navController.navigate(GeofenceRoutes.LIST) },
        )
    }
}
