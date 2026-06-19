package com.blizzardcaron.freeolleefaces

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import com.blizzardcaron.freeolleefaces.auto.AlarmRearm
import com.blizzardcaron.freeolleefaces.auto.AndroidAlarmScheduler
import com.blizzardcaron.freeolleefaces.auto.AndroidScheduler
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.ble.AndroidWatchConnection
import com.blizzardcaron.freeolleefaces.health.AndroidStepsProvider
import com.blizzardcaron.freeolleefaces.health.StepsProvider
import com.blizzardcaron.freeolleefaces.location.AndroidLocationProvider
import com.blizzardcaron.freeolleefaces.notifications.AndroidNotificationAccess
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.alarmSettings
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import com.blizzardcaron.freeolleefaces.prefs.timerSettings
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.blizzardcaron.freeolleefaces.ui.BondedDevice
import com.blizzardcaron.freeolleefaces.ui.BondedDevicesDialog
import com.blizzardcaron.freeolleefaces.ui.BottomNavTab
import com.blizzardcaron.freeolleefaces.ui.AlarmsScreen
import com.blizzardcaron.freeolleefaces.ui.HomeCallbacks
import com.blizzardcaron.freeolleefaces.ui.HomeScreen
import com.blizzardcaron.freeolleefaces.ui.Screen
import com.blizzardcaron.freeolleefaces.ui.SettingsCallbacks
import com.blizzardcaron.freeolleefaces.ui.SettingsScreen
import com.blizzardcaron.freeolleefaces.ui.TimerSetEditScreen
import com.blizzardcaron.freeolleefaces.ui.TimerSetsScreen
import com.blizzardcaron.freeolleefaces.ui.theme.FreeOlleeFacesTheme
import com.blizzardcaron.freeolleefaces.ui.versionLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeOlleeFacesTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val viewModel = remember {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        AppViewModel(
            prefs = Prefs(appSettings(context)),
            ble = AndroidBleClient(context),
            watchConnection = AndroidWatchConnection(context),
            steps = AndroidStepsProvider(context),
            location = AndroidLocationProvider(context),
            notificationAccess = AndroidNotificationAccess(context),
            timerRepo = TimerSetsRepository(timerSettings(context)),
            scheduler = AndroidScheduler(context),
            alarmRepo = AlarmsRepository(alarmSettings(context)),
            alarmScheduler = AndroidAlarmScheduler(context),
            versionLabel = versionLabel(versionName, context.packageName),
        )
    }
    val state = viewModel.state
    val screen = viewModel.screen
    val kotlinScope = rememberCoroutineScope()

    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    // Alarm BLE pushes run detached from the ViewModel (debounced in AlarmRearm, also fired by
    // receivers) — confirm their outcomes through the same snackbar.
    LaunchedEffect(Unit) {
        AlarmRearm.pushResults.collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(Unit) {
        val hasAnyLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val haveCoords = viewModel.hasSavedCoords()
        val stale = viewModel.locationIsStale()

        when {
            // Fresh saved coords: use them silently, no fix.
            haveCoords && !stale -> { /* render with saved coords */ }

            // Stale saved coords + permission: render saved, silently refresh.
            haveCoords && hasAnyLocation -> {
                viewModel.setLocating(true)
                viewModel.fetchLocation()
                    .onSuccess { coords -> viewModel.onLocationFetchedSilent(coords.lat, coords.lng) }
                    .onFailure { viewModel.onLocationRefreshFailed() }
            }

            // No saved coords, permission held: first-run fix.
            !haveCoords && hasAnyLocation -> {
                viewModel.setLocating(true)
                viewModel.fetchLocation()
                    .onSuccess { coords -> viewModel.onLocationFetchedSilent(coords.lat, coords.lng) }
                    .onFailure { viewModel.setLocating(false) }
            }

            // No permission and no saved coords: the user sets location in Settings.
            else -> { /* nothing to fetch */ }
        }

        viewModel.refreshActive(force = false, push = false)
        viewModel.onStart()
    }

    // Dashboard polling: while Home is visible, refresh all face previews on entry and every 60 s.
    // Keyed on `screen` so it cancels when you navigate away and restarts on return.
    LaunchedEffect(screen) {
        if (screen == Screen.Home) {
            while (true) {
                viewModel.refreshAllPreviews()
                delay(60_000)
            }
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showPicker = true
        else viewModel.onBluetoothDenied()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — errors still record in-app either way */ }

    LaunchedEffect(Unit) {
        val backgroundActive = viewModel.backgroundActive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backgroundActive &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Re-check notification access/count when the activity resumes — e.g. returning from the
    // system notification-access settings page. The 60 s dashboard poll alone would lag here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                Lifecycle.Event.ON_RESUME -> viewModel.onResumeNotifications()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            viewModel.setLocating(true)
            kotlinScope.launch {
                viewModel.fetchLocation()
                    .onSuccess { coords -> viewModel.onLocationFetched(coords.lat, coords.lng) }
                    .onFailure { err -> viewModel.onLocationFailed(err.message) }
            }
        } else {
            viewModel.onLocationDenied()
        }
    }

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        // The read permission alone is enough to show steps; background read just lets the
        // worker keep updating while the app is closed.
        viewModel.refreshSteps(push = viewModel.activeIsSteps())
        if (!granted.containsAll(AndroidStepsProvider.PERMISSIONS)) {
            viewModel.onPartialHealthGrant()
        }
    }

    fun selectWatch() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        ) showPicker = true
        else btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun useMyLocation() {
        val hasAny =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (hasAny) {
            viewModel.setLocating(true)
            kotlinScope.launch {
                viewModel.fetchLocation()
                    .onSuccess { coords -> viewModel.onLocationFetched(coords.lat, coords.lng) }
                    .onFailure { err -> viewModel.onLocationFailed(err.message) }
            }
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    val homeCallbacks = HomeCallbacks(
        onActivate = { viewModel.activate(it) },
        onUpdateNow = { viewModel.refreshActive(force = true, push = true) },
        onTempUnitChange = { newUnit -> viewModel.setTempUnit(newUnit) },
        onCustomChange = { text -> viewModel.setCustomText(text) },
        onSendCustom = { viewModel.sendCustom(state.custom) },
        onGrantHealth = {
            when (viewModel.healthAvailability()) {
                StepsProvider.Availability.AVAILABLE ->
                    healthPermissionLauncher.launch(AndroidStepsProvider.PERMISSIONS)
                StepsProvider.Availability.UPDATE_REQUIRED ->
                    viewModel.onHealthUpdateRequired()
                StepsProvider.Availability.UNAVAILABLE ->
                    viewModel.onHealthUnavailable()
            }
        },
        onGrantNotificationAccess = { openNotificationAccessSettings(context) },
        onToggleNotifications = { viewModel.setNotificationsEnabled(it) },
        onNotificationsUpdateNow = { viewModel.pushCountIfWatch() },
        onReconnect = { viewModel.onReconnect() },
    )

    val settingsCallbacks = SettingsCallbacks(
        onBack = { viewModel.navigateTo(Screen.Home) },
        onSelectWatch = ::selectWatch,
        onIntervalChange = { mins -> viewModel.setInterval(mins) },
        onSleepEnabledChange = { enabled -> viewModel.setSleepEnabled(enabled) },
        onSleepStartChange = { min -> viewModel.setSleepStart(min) },
        onSleepEndChange = { min -> viewModel.setSleepEnd(min) },
        onAutoSleepScheduleEnabledChange = { on -> viewModel.setAutoSleepScheduleEnabled(on) },
        onAutoSleepWindowStartChange = { min -> viewModel.setAutoSleepWindowStart(min) },
        onAutoSleepWindowEndChange = { min -> viewModel.setAutoSleepWindowEnd(min) },
        onAutoSleepInWindowOnChange = { on -> viewModel.setAutoSleepInWindowOn(on) },
        onAutoSleepInWindowPeriodChange = { sec -> viewModel.setAutoSleepInWindowPeriod(sec) },
        onAutoSleepOutWindowOnChange = { on -> viewModel.setAutoSleepOutWindowOn(on) },
        onAutoSleepOutWindowPeriodChange = { sec -> viewModel.setAutoSleepOutWindowPeriod(sec) },
        onLatChange = { viewModel.onCoordEdit(it, state.lng) },
        onLngChange = { viewModel.onCoordEdit(state.lat, it) },
        onUseMyLocation = ::useMyLocation,
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (BottomNavTab.showsBottomBar(screen)) {
                NavigationBar {
                    BottomNavTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = BottomNavTab.forScreen(screen) == tab,
                            onClick = {
                                when (tab) {
                                    BottomNavTab.Alarm -> viewModel.alarms.refreshAlarms()
                                    BottomNavTab.Timer -> viewModel.timers.refreshTimers()
                                    else -> {}
                                }
                                viewModel.navigateTo(tab.screen)
                            },
                            icon = { Text(tab.glyph, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        val modifier = Modifier.padding(inner)
        when (screen) {
        Screen.Home -> HomeScreen(state = state, callbacks = homeCallbacks, modifier = modifier)
        Screen.Settings -> SettingsScreen(
            state = state,
            callbacks = settingsCallbacks,
            onReconnect = { viewModel.onReconnect() },
            modifier = modifier,
        )
        Screen.TimerSets -> TimerSetsScreen(
            sets = viewModel.timers.timerSets,
            activeId = viewModel.timers.timerActiveId,
            sending = state.sending,
            quickTimerSeconds = viewModel.timers.quickTimerSeconds,
            quickTimerStartFromApp = viewModel.timers.quickTimerStartFromApp,
            quickTimerIntervalMode = viewModel.timers.quickTimerIntervalMode,
            onSaveQuick = { viewModel.timers.saveQuickTimer(it) },
            onToggleStartFromApp = { viewModel.timers.toggleQuickTimerStartFromApp(it) },
            onToggleIntervalMode = { viewModel.timers.toggleQuickTimerIntervalMode(it) },
            onSendQuick = { viewModel.timers.sendQuickTimer() },
            quickTimerAlarmMode = viewModel.timers.quickTimerAlarmMode,
            quickTimerAlarmHour = viewModel.timers.quickTimerAlarmHour,
            quickTimerAlarmMinute = viewModel.timers.quickTimerAlarmMinute,
            onToggleAlarmMode = { viewModel.timers.toggleQuickTimerAlarmMode(it) },
            onSaveAlarmTime = { h, m -> viewModel.timers.saveQuickTimerAlarmTime(h, m) },
            onSendAlarm = { viewModel.timers.sendQuickAlarm() },
            onOpen = { viewModel.timers.editTimerSet(it); viewModel.navigateTo(Screen.TimerSetEdit) },
            onNew = { viewModel.timers.newTimerSet(); viewModel.navigateTo(Screen.TimerSetEdit) },
            onDuplicate = { src -> viewModel.timers.duplicateTimerSet(src) },
            onDelete = { viewModel.timers.deleteTimerSet(it) },
            onSend = { viewModel.timers.sendTimerSet(it) },
            onStart = { viewModel.timers.startTimerSet(it) },
            onMoveUp = { viewModel.timers.moveTimerSetUp(it) },
            onMoveDown = { viewModel.timers.moveTimerSetDown(it) },
            onBack = { viewModel.navigateTo(Screen.Home) },
            connectionStatus = state.connectionStatus,
            onReconnect = { viewModel.onReconnect() },
            modifier = modifier,
        )
        Screen.Alarms -> AlarmsScreen(
            alarms = viewModel.alarms.items,
            nextSummary = viewModel.alarms.nextAlarmSummary,
            onAdd = { viewModel.alarms.addAlarm() },
            onSave = { viewModel.alarms.saveAlarm(it) },
            onToggle = { id, enabled -> viewModel.alarms.toggleAlarm(id, enabled) },
            onDelete = { viewModel.alarms.deleteAlarm(it) },
            onBack = { viewModel.navigateTo(Screen.Home) },
            connectionStatus = state.connectionStatus,
            onReconnect = { viewModel.onReconnect() },
            modifier = modifier,
        )
        Screen.TimerSetEdit -> {
            val editing = viewModel.timers.editingSet
            if (editing == null) {
                viewModel.navigateTo(Screen.TimerSets)
            } else {
                TimerSetEditScreen(
                    set = editing,
                    onSave = { s -> viewModel.timers.saveTimerSet(s); viewModel.navigateTo(Screen.TimerSets) },
                    onSend = { s ->
                        viewModel.timers.saveTimerSet(s)
                        viewModel.timers.sendTimerSet(s)
                        viewModel.navigateTo(Screen.TimerSets)
                    },
                    onBack = { viewModel.navigateTo(Screen.TimerSets) },
                    connectionStatus = state.connectionStatus,
                    onReconnect = { viewModel.onReconnect() },
                    modifier = modifier,
                )
            }
        }
        }
    }

    if (showPicker) {
        val devices = bondedDevices(context)
        BondedDevicesDialog(
            devices = devices,
            onPick = { device ->
                viewModel.onWatchPicked(device.address, "Watch: ${device.name ?: device.address}")
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

private fun openNotificationAccessSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@SuppressLint("MissingPermission")
private fun bondedDevices(context: Context): List<BondedDevice> {
    val mgr = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
    val adapter = mgr.adapter ?: return emptyList()
    if (!adapter.isEnabled) return emptyList()
    return adapter.bondedDevices?.map { BondedDevice(it.name, it.address) }.orEmpty()
}
