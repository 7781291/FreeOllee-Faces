package com.blizzardcaron.freeolleefaces

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.blizzardcaron.freeolleefaces.activity.AndroidActivityTrackStore
import com.blizzardcaron.freeolleefaces.ui.ActivityCallbacks
import com.blizzardcaron.freeolleefaces.ui.ActivityDetailScreen
import com.blizzardcaron.freeolleefaces.ui.ActivityHistoryCallbacks
import com.blizzardcaron.freeolleefaces.ui.ActivityHistoryScreen
import com.blizzardcaron.freeolleefaces.ui.ActivityScreen
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.InstrumentsCallbacks
import com.blizzardcaron.freeolleefaces.ui.InstrumentsScreen
import com.blizzardcaron.freeolleefaces.ui.Screen

@Composable
fun ActivityTab(viewModel: AppViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val activityState by viewModel.activity.state.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.activity.onStart() }
    val startWithPermission: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.activity.onStart()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    ActivityScreen(
        state = activityState,
        unit = viewModel.activity.activityUnit,
        watchSelected = viewModel.activity.watchSelected,
        lastSummary = AndroidActivityTrackStore(context).latest()?.summary,
        callbacks = ActivityCallbacks(
            onStart = startWithPermission,
            onStop = { viewModel.activity.onStop() },
            onMode = { viewModel.activity.onMode() },
            onToggleUnit = { viewModel.activity.toggleUnit() },
            onOpenHistory = { viewModel.navigateTo(Screen.ActivityHistory) },
        ),
        modifier = modifier,
    )
}

@Composable
fun InstrumentsTab(viewModel: AppViewModel, state: HomeState, modifier: Modifier) {
    val context = LocalContext.current
    val instrumentsState by viewModel.instruments.state.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> viewModel.instruments.onStart() }
    val startWithPermission: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.instruments.onStart()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    InstrumentsScreen(
        state = instrumentsState,
        unit = viewModel.instruments.activityUnit,
        tempUnit = state.tempUnit,
        watchSelected = viewModel.instruments.watchSelected,
        callbacks = InstrumentsCallbacks(
            onStart = startWithPermission,
            onStop = { viewModel.instruments.onStop() },
            onMode = { viewModel.instruments.onMode() },
            onToggleUnit = { viewModel.instruments.toggleUnit() },
        ),
        modifier = modifier,
    )
}

@Composable
fun ActivityHistoryTab(viewModel: AppViewModel, modifier: Modifier) {
    viewModel.historyRevision // subscribe: recompose after a delete
    ActivityHistoryScreen(
        tracks = viewModel.activityHistory(),
        unit = viewModel.activity.activityUnit,
        callbacks = ActivityHistoryCallbacks(
            onOpen = { viewModel.openActivity(it) },
            onDelete = { viewModel.deleteActivity(it) },
            onBack = { viewModel.navigateTo(Screen.Activity) },
        ),
        modifier = modifier,
    )
}

@Composable
fun ActivityDetailTab(viewModel: AppViewModel, modifier: Modifier) {
    val track = viewModel.activityHistory().firstOrNull { it.id == viewModel.selectedActivityId }
    if (track == null) {
        viewModel.navigateTo(Screen.ActivityHistory)
    } else {
        ActivityDetailScreen(
            track = track,
            unit = viewModel.activity.activityUnit,
            onBack = { viewModel.navigateTo(Screen.ActivityHistory) },
            modifier = modifier,
        )
    }
}
