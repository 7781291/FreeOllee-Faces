package com.blizzardcaron.freeolleefaces

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
import com.blizzardcaron.freeolleefaces.auto.AutoUpdateSchedule
import com.blizzardcaron.freeolleefaces.auto.AutoUpdateScheduler
import com.blizzardcaron.freeolleefaces.auto.SleepWindow
import com.blizzardcaron.freeolleefaces.auto.isTempCacheFresh
import com.blizzardcaron.freeolleefaces.ble.OlleeBleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.format.WeatherErrorCopy
import com.blizzardcaron.freeolleefaces.location.LocationSource
import com.blizzardcaron.freeolleefaces.location.freshnessLabel
import com.blizzardcaron.freeolleefaces.location.isLocationStale
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.sun.SunCalc
import com.blizzardcaron.freeolleefaces.ui.BondedDevicesDialog
import com.blizzardcaron.freeolleefaces.ui.HomeCallbacks
import com.blizzardcaron.freeolleefaces.ui.HomeScreen
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.FacesListScreen
import com.blizzardcaron.freeolleefaces.ui.PreviewState
import com.blizzardcaron.freeolleefaces.ui.Screen
import com.blizzardcaron.freeolleefaces.ui.theme.FreeOlleeFacesTheme
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import com.blizzardcaron.freeolleefaces.weather.RetryPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeOlleeFacesTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { inner ->
                    AppRoot(snackbarHostState, Modifier.padding(inner))
                }
            }
        }
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

private fun clockTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(CLOCK)

private fun locLabel(lat: Double?, lng: Double?): String =
    if (lat != null && lng != null) "Location: %.4f, %.4f".format(lat, lng) else "Location: not set"

@Composable
private fun AppRoot(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val ble = remember { OlleeBleClient(context) }
    val locationSource = remember { LocationSource(context) }
    val scope = rememberCoroutineScope()

    fun showSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var showPicker by remember { mutableStateOf(false) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    var state by remember {
        mutableStateOf(
            HomeState(
                activeFace = prefs.activeFace,
                lat = prefs.lastLat?.toString() ?: "",
                lng = prefs.lastLng?.toString() ?: "",
                watchLabel = labelForAddress(context, prefs.watchAddress),
                watchSelected = prefs.watchAddress != null,
                tempUnit = prefs.tempUnit,
                tempIntervalText = prefs.tempIntervalMinutes.toString(),
                sleepEnabled = prefs.sleepEnabled,
                sleepStartMin = prefs.sleepStartMin,
                sleepEndMin = prefs.sleepEndMin,
                custom = prefs.customText,
                customSent = prefs.customSentMs?.let { "Sent '${prefs.customText}' at ${clockTime(it)}" },
                locationLabel = locLabel(prefs.lastLat, prefs.lastLng),
                locationFreshness = freshnessLabel(prefs.lastLocationFetchedMs, System.currentTimeMillis()),
            )
        )
    }

    fun update(transform: (HomeState) -> HomeState) { state = transform(state) }

    fun validCoords(): Pair<Double, Double>? {
        val lat = state.lat.toDoubleOrNull(); val lng = state.lng.toDoubleOrNull()
        return if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
            lat to lng
        } else null
    }

    fun pushIfWatch(payload: String) {
        val addr = prefs.watchAddress ?: return
        scope.launch { sendAndReport(ble, addr, payload, ::update, ::showSnackbar) }
    }

    fun sendCustom(text: String) {
        val addr = prefs.watchAddress ?: return
        prefs.customText = text
        scope.launch {
            val result = sendAndReport(ble, addr, DisplayFormatter.custom(text), ::update, ::showSnackbar)
            if (result.isSuccess) {
                prefs.customSentMs = System.currentTimeMillis()
                update { it.copy(customSent = "Sent '$text' at ${clockTime(prefs.customSentMs!!)}") }
            }
        }
    }

    fun interval(): Int = state.tempIntervalText.toIntOrNull()?.coerceAtLeast(15) ?: 60

    fun tempNextText(): String {
        val sleep = if (state.sleepEnabled) SleepWindow(state.sleepStartMin, state.sleepEndMin) else null
        val fire = AutoUpdateSchedule.nextTemperatureFire(
            ZonedDateTime.now(ZoneId.systemDefault()), interval(), sleep,
        )
        return "Next update ${fire.format(CLOCK)}"
    }

    fun refreshTemp(force: Boolean, push: Boolean) {
        val c = validCoords()
        if (c == null) {
            update { it.copy(
                tempPreview = PreviewState.Error("Enter coordinates to see temperature"),
                showLocationFallback = true,
            ) }
            return
        }
        val (lat, lng) = c
        if (!force && isTempCacheFresh(
                prefs.tempFetchedMs, prefs.tempCacheUnit, state.tempUnit, interval(), System.currentTimeMillis(),
            )
        ) {
            val cached = prefs.tempValue!!
            val payload = DisplayFormatter.temperature(cached, state.tempUnit)
            val human = "Currently: %.1f°%s".format(Locale.US, cached, state.tempUnit.symbol)
            update { it.copy(
                tempPreview = PreviewState.Ready(payload, human),
                tempUpdated = "Updated ${clockTime(prefs.tempFetchedMs!!)}",
                tempNext = tempNextText(),
            ) }
            if (push) pushIfWatch(payload)
            return
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            update { it.copy(tempPreview = PreviewState.Loading) }
            OpenMeteoClient.currentTemp(lat, lng, state.tempUnit, RetryPolicy.Preview)
                .onSuccess { temp ->
                    prefs.recordTempFetch(temp, state.tempUnit)
                    val payload = DisplayFormatter.temperature(temp, state.tempUnit)
                    val human = "Currently: %.1f°%s".format(Locale.US, temp, state.tempUnit.symbol)
                    update { it.copy(
                        tempPreview = PreviewState.Ready(payload, human),
                        tempUpdated = "Updated ${clockTime(prefs.tempFetchedMs!!)}",
                        tempNext = tempNextText(),
                    ) }
                    if (push) pushIfWatch(payload)
                }
                .onFailure { err ->
                    update { it.copy(tempPreview = PreviewState.Error(WeatherErrorCopy.describe(err))) }
                }
        }
    }

    fun refreshSun(push: Boolean) {
        val c = validCoords()
        if (c == null) {
            update { it.copy(
                sunPreview = PreviewState.Error("Enter coordinates to see sun event"),
                showLocationFallback = true,
            ) }
            return
        }
        val (lat, lng) = c
        val event = SunCalc.nextEvent(Instant.now(), lat, lng, ZoneId.systemDefault())
        if (event == null) {
            update { it.copy(sunPreview = PreviewState.NoEvent, sunNext = null) }
            return
        }
        val payload = DisplayFormatter.sunTime(event.kind, event.time.toLocalTime())
        val pretty = event.time.format(CLOCK)
        val kindLabel = event.kind.name.lowercase().replaceFirstChar { it.uppercase() }
        update { it.copy(
            sunPreview = PreviewState.Ready(payload, "$kindLabel at $pretty"),
            sunUpdated = "Updated ${clockTime(System.currentTimeMillis())}",
            sunNext = "Next: $kindLabel at $pretty",
        ) }
        if (push) pushIfWatch(payload)
    }

    fun refreshActive(force: Boolean, push: Boolean) {
        when (state.activeFace) {
            ActiveFace.TEMPERATURE -> refreshTemp(force, push)
            ActiveFace.SUN -> refreshSun(push)
            ActiveFace.CUSTOM -> {}
        }
    }

    fun activate(face: ActiveFace) {
        prefs.activeFace = face
        update { it.copy(activeFace = face) }
        screen = Screen.Home
        AutoUpdateScheduler.reschedule(context)
        when (face) {
            ActiveFace.TEMPERATURE -> refreshTemp(force = false, push = true)
            ActiveFace.SUN -> refreshSun(push = true)
            ActiveFace.CUSTOM -> {
                update { it.copy(showLocationFallback = false) }
                val text = prefs.customText
                if (text.isNotEmpty()) sendCustom(text)
            }
        }
    }

    fun onCoordEdit(lat: String, lng: String) {
        update { it.copy(lat = lat, lng = lng) }
        val latD = lat.toDoubleOrNull(); val lngD = lng.toDoubleOrNull()
        if (latD != null && lngD != null && latD in -90.0..90.0 && lngD in -180.0..180.0) {
            prefs.lastLat = latD; prefs.lastLng = lngD
            prefs.lastLocationFetchedMs = System.currentTimeMillis()
            update { it.copy(
                locationLabel = locLabel(latD, lngD),
                locationFreshness = "just now",
            ) }
        }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(500)
            refreshActive(force = false, push = false)
        }
    }

    LaunchedEffect(Unit) {
        val hasAnyLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val haveCoords = prefs.lastLat != null && prefs.lastLng != null
        val stale = isLocationStale(prefs.lastLocationFetchedMs, System.currentTimeMillis())

        when {
            // Fresh saved coords: use them silently, no fix.
            haveCoords && !stale -> { /* render with saved coords */ }

            // Stale saved coords + permission: render saved, silently refresh.
            haveCoords && hasAnyLocation -> {
                update { it.copy(locating = true) }
                locationSource.fetch()
                    .onSuccess { coords ->
                        prefs.lastLat = coords.lat; prefs.lastLng = coords.lng
                        prefs.lastLocationFetchedMs = System.currentTimeMillis()
                        update { it.copy(
                            lat = coords.lat.toString(),
                            lng = coords.lng.toString(),
                            showLocationFallback = false,
                            locating = false,
                            locationLabel = locLabel(coords.lat, coords.lng),
                            locationFreshness = "just now",
                        ) }
                    }
                    .onFailure {
                        update { it.copy(
                            locating = false,
                            locationFreshness = (freshnessLabel(
                                prefs.lastLocationFetchedMs, System.currentTimeMillis(),
                            ) ?: "stale") + " · refresh failed",
                        ) }
                        showSnackbar("Couldn't refresh location — using saved coordinates")
                    }
            }

            // No saved coords, permission held: first-run fix.
            !haveCoords && hasAnyLocation -> {
                update { it.copy(locating = true) }
                locationSource.fetch()
                    .onSuccess { coords ->
                        prefs.lastLat = coords.lat; prefs.lastLng = coords.lng
                        prefs.lastLocationFetchedMs = System.currentTimeMillis()
                        update { it.copy(
                            lat = coords.lat.toString(),
                            lng = coords.lng.toString(),
                            showLocationFallback = false,
                            locating = false,
                            locationLabel = locLabel(coords.lat, coords.lng),
                            locationFreshness = "just now",
                        ) }
                    }
                    .onFailure {
                        update { it.copy(
                            locating = false,
                            showLocationFallback = state.activeFace != ActiveFace.CUSTOM,
                        ) }
                    }
            }

            // No permission: manual entry / grant fallback (Custom needs no coords).
            else -> {
                update { it.copy(showLocationFallback = state.activeFace != ActiveFace.CUSTOM) }
            }
        }

        refreshActive(force = false, push = false)
        AutoUpdateScheduler.reschedule(context)
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showPicker = true
        else showSnackbar("Bluetooth permission denied — can't list paired watches.")
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — errors still record in-app either way */ }

    LaunchedEffect(Unit) {
        val backgroundActive = prefs.activeFace != ActiveFace.CUSTOM && prefs.watchAddress != null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backgroundActive &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            scope.launch {
                update { it.copy(locating = true) }
                locationSource.fetch()
                    .onSuccess { coords ->
                        prefs.lastLat = coords.lat; prefs.lastLng = coords.lng
                        prefs.lastLocationFetchedMs = System.currentTimeMillis()
                        update { it.copy(
                            lat = coords.lat.toString(),
                            lng = coords.lng.toString(),
                            showLocationFallback = false,
                            locating = false,
                            locationLabel = locLabel(coords.lat, coords.lng),
                            locationFreshness = "just now",
                        ) }
                        refreshActive(force = true, push = false)
                    }
                    .onFailure { err ->
                        update { it.copy(locating = false) }
                        showSnackbar("Location failed: ${err.message}")
                    }
            }
        } else {
            showSnackbar("Location permission denied — enter coordinates manually.")
        }
    }

    val callbacks = HomeCallbacks(
        onOpenFaces = { screen = Screen.FacesList },
        onSelectWatch = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
            ) showPicker = true
            else btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        },
        onUpdateNow = { refreshActive(force = true, push = true) },
        onTempUnitChange = { newUnit ->
            prefs.tempUnit = newUnit
            update { it.copy(tempUnit = newUnit) }
            refreshTemp(force = false, push = state.activeFace == ActiveFace.TEMPERATURE)
        },
        onTempIntervalChange = { text ->
            update { it.copy(tempIntervalText = text) }
            val mins = text.toIntOrNull()
            if (mins != null) {
                prefs.tempIntervalMinutes = mins.coerceAtLeast(15)
                AutoUpdateScheduler.reschedule(context)
                update { it.copy(tempNext = tempNextText()) }
            }
        },
        onSleepEnabledChange = { enabled ->
            prefs.sleepEnabled = enabled
            update { it.copy(sleepEnabled = enabled, tempNext = tempNextText()) }
            AutoUpdateScheduler.reschedule(context)
        },
        onSleepStartChange = { min ->
            prefs.sleepStartMin = min
            update { it.copy(sleepStartMin = min, tempNext = tempNextText()) }
            AutoUpdateScheduler.reschedule(context)
        },
        onSleepEndChange = { min ->
            prefs.sleepEndMin = min
            update { it.copy(sleepEndMin = min, tempNext = tempNextText()) }
            AutoUpdateScheduler.reschedule(context)
        },
        onCustomChange = { text ->
            val capped = text.take(6)
            update { it.copy(custom = capped) }
            prefs.customText = capped
        },
        onSendCustom = { sendCustom(state.custom) },
        onLatChange = { onCoordEdit(it, state.lng) },
        onLngChange = { onCoordEdit(state.lat, it) },
        onUseMyLocation = {
            val hasAny =
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            if (hasAny) {
                scope.launch {
                    update { it.copy(locating = true) }
                    locationSource.fetch()
                        .onSuccess { coords ->
                            prefs.lastLat = coords.lat; prefs.lastLng = coords.lng
                            prefs.lastLocationFetchedMs = System.currentTimeMillis()
                            update { it.copy(
                                lat = coords.lat.toString(),
                                lng = coords.lng.toString(),
                                showLocationFallback = false,
                                locating = false,
                                locationLabel = locLabel(coords.lat, coords.lng),
                                locationFreshness = "just now",
                            ) }
                            refreshActive(force = true, push = false)
                        }
                        .onFailure { err ->
                            update { it.copy(locating = false) }
                            showSnackbar("Location failed: ${err.message}")
                        }
                }
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            }
        },
    )

    when (screen) {
        Screen.Home -> HomeScreen(state = state, callbacks = callbacks, modifier = modifier)
        Screen.FacesList -> FacesListScreen(
            active = state.activeFace,
            onSelect = { activate(it) },
            onBack = { screen = Screen.Home },
            modifier = modifier,
        )
    }

    if (showPicker) {
        val devices = bondedDevices(context)
        BondedDevicesDialog(
            devices = devices,
            onPick = { device ->
                prefs.watchAddress = device.address
                update { it.copy(
                    watchLabel = "Watch: ${device.name ?: device.address}",
                    watchSelected = true,
                ) }
                showPicker = false
                when (state.activeFace) {
                    ActiveFace.CUSTOM -> prefs.customText.takeIf { it.isNotEmpty() }?.let { sendCustom(it) }
                    else -> refreshActive(force = false, push = true)
                }
            },
            onDismiss = { showPicker = false },
        )
    }
}

private suspend fun sendAndReport(
    ble: OlleeBleClient,
    address: String,
    value: String,
    update: ((HomeState) -> HomeState) -> Unit,
    showSnackbar: (String) -> Unit,
    target: Int = OlleeProtocol.TARGET_NAMEPLATE,
): Result<Unit> {
    update { it.copy(sending = true) }
    return ble.send(address, value, target)
        .onSuccess { update { it.copy(sending = false) }; showSnackbar("Sent '$value'") }
        .onFailure { err -> update { it.copy(sending = false) }; showSnackbar("Send failed: ${err.message}") }
}

@SuppressLint("MissingPermission")
private fun bondedDevices(context: Context): List<BluetoothDevice> {
    val mgr = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
    val adapter = mgr.adapter ?: return emptyList()
    if (!adapter.isEnabled) return emptyList()
    return adapter.bondedDevices?.toList().orEmpty()
}

@SuppressLint("MissingPermission")
private fun labelForAddress(context: Context, address: String?): String {
    if (address == null) return "Watch: none selected"
    val mgr = context.getSystemService(BluetoothManager::class.java) ?: return "Watch: $address"
    val device = mgr.adapter?.getRemoteDevice(address)
    return "Watch: ${device?.name ?: address}"
}
