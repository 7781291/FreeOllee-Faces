package com.blizzardcaron.freeolleefaces

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import com.blizzardcaron.freeolleefaces.auto.AlarmRearm
import com.blizzardcaron.freeolleefaces.auto.AndroidAlarmScheduler
import com.blizzardcaron.freeolleefaces.auto.AndroidScheduler
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.ble.AndroidWatchConnection
import com.blizzardcaron.freeolleefaces.health.AndroidStepsProvider
import com.blizzardcaron.freeolleefaces.location.AndroidLocationProvider
import com.blizzardcaron.freeolleefaces.notifications.AndroidNotificationAccess
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.alarmSettings
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import com.blizzardcaron.freeolleefaces.prefs.timerSettings
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.blizzardcaron.freeolleefaces.ui.AlarmsScreen
import com.blizzardcaron.freeolleefaces.ui.BondedDevice
import com.blizzardcaron.freeolleefaces.ui.BondedDevicesDialog
import com.blizzardcaron.freeolleefaces.ui.BottomNavTab
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
        val haveCoords = viewModel.complications.hasSavedCoords()
        val stale = viewModel.complications.locationIsStale()

        when {
            // Fresh saved coords: use them silently, no fix.
            haveCoords && !stale -> { /* render with saved coords */ }

            // Stale saved coords + permission: render saved, silently refresh.
            haveCoords && hasAnyLocation -> {
                viewModel.complications.setLocating(true)
                viewModel.complications.fetchLocation()
                    .onSuccess { coords -> viewModel.complications.onLocationFetchedSilent(coords.lat, coords.lng) }
                    .onFailure { viewModel.complications.onLocationRefreshFailed() }
            }

            // No saved coords, permission held: first-run fix.
            !haveCoords && hasAnyLocation -> {
                viewModel.complications.setLocating(true)
                viewModel.complications.fetchLocation()
                    .onSuccess { coords -> viewModel.complications.onLocationFetchedSilent(coords.lat, coords.lng) }
                    .onFailure { viewModel.complications.setLocating(false) }
            }

            // No permission and no saved coords: the user sets location in Settings.
            else -> { /* nothing to fetch */ }
        }

        viewModel.complications.refreshActive(force = false, push = false)
        viewModel.onStart()
    }

    // Dashboard polling: while Home is visible, refresh all face previews on entry and every 60 s.
    // Keyed on `screen` so it cancels when you navigate away and restarts on return.
    LaunchedEffect(screen) {
        if (screen == Screen.Home) {
            while (true) {
                viewModel.complications.refreshAllPreviews()
                delay(60_000)
            }
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
                Lifecycle.Event.ON_RESUME -> viewModel.complications.onResumeNotifications()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val perms = rememberPermissionsCoordinator(
        PermissionsCallbacks(
            backgroundActive = { viewModel.backgroundActive() },
            onBluetoothGranted = { showPicker = true },
            onBluetoothDenied = { viewModel.onBluetoothDenied() },
            setLocating = { viewModel.complications.setLocating(it) },
            onLocationFetched = { lat, lng -> viewModel.complications.onLocationFetched(lat, lng) },
            onLocationFailed = { msg -> viewModel.complications.onLocationFailed(msg) },
            onLocationDenied = { viewModel.complications.onLocationDenied() },
            fetchLocation = { viewModel.complications.fetchLocation() },
            refreshSteps = { push -> viewModel.complications.refreshSteps(push) },
            activeIsSteps = { viewModel.complications.activeIsSteps() },
            onPartialHealthGrant = { viewModel.complications.onPartialHealthGrant() },
            healthAvailability = { viewModel.complications.healthAvailability() },
            onHealthUpdateRequired = { viewModel.complications.onHealthUpdateRequired() },
            onHealthUnavailable = { viewModel.complications.onHealthUnavailable() },
        )
    )

    val homeCallbacks = HomeCallbacks(
        onActivate = { viewModel.complications.activate(it) },
        onUpdateNow = { viewModel.complications.refreshActive(force = true, push = true) },
        onTempUnitChange = { newUnit -> viewModel.complications.setTempUnit(newUnit) },
        onCustomChange = { text -> viewModel.complications.setCustomText(text) },
        onSendCustom = { viewModel.complications.sendCustom(state.custom) },
        onGrantHealth = { perms.requestHealth() },
        onGrantNotificationAccess = { openNotificationAccessSettings(context) },
        onToggleNotifications = { viewModel.complications.setNotificationsEnabled(it) },
        onNotificationsUpdateNow = { viewModel.complications.pushCountIfWatch() },
        onReconnect = { viewModel.onReconnect() },
    )

    val settingsCallbacks = SettingsCallbacks(
        onBack = { viewModel.navigateTo(Screen.Home) },
        onSelectWatch = perms::selectWatch,
        onIntervalChange = { mins -> viewModel.settings.setInterval(mins) },
        onSleepEnabledChange = { enabled -> viewModel.settings.setSleepEnabled(enabled) },
        onSleepStartChange = { min -> viewModel.settings.setSleepStart(min) },
        onSleepEndChange = { min -> viewModel.settings.setSleepEnd(min) },
        onAutoSleepScheduleEnabledChange = { on -> viewModel.settings.setAutoSleepScheduleEnabled(on) },
        onAutoSleepWindowStartChange = { min -> viewModel.settings.setAutoSleepWindowStart(min) },
        onAutoSleepWindowEndChange = { min -> viewModel.settings.setAutoSleepWindowEnd(min) },
        onAutoSleepInWindowOnChange = { on -> viewModel.settings.setAutoSleepInWindowOn(on) },
        onAutoSleepInWindowPeriodChange = { sec -> viewModel.settings.setAutoSleepInWindowPeriod(sec) },
        onAutoSleepOutWindowOnChange = { on -> viewModel.settings.setAutoSleepOutWindowOn(on) },
        onAutoSleepOutWindowPeriodChange = { sec -> viewModel.settings.setAutoSleepOutWindowPeriod(sec) },
        onLatChange = { viewModel.settings.onCoordEdit(it, state.lng) },
        onLngChange = { viewModel.settings.onCoordEdit(state.lat, it) },
        onUseMyLocation = perms::useMyLocation,
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
                onOpen = {
                    viewModel.timers.editTimerSet(it)
                    viewModel.navigateTo(Screen.TimerSetEdit)
                },
                onNew = {
                    viewModel.timers.newTimerSet()
                    viewModel.navigateTo(Screen.TimerSetEdit)
                },
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
                        onSave = { s ->
                            viewModel.timers.saveTimerSet(s)
                            viewModel.navigateTo(Screen.TimerSets)
                        },
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
