package com.blizzardcaron.freeolleefaces

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import com.blizzardcaron.freeolleefaces.health.AndroidStepsProvider
import com.blizzardcaron.freeolleefaces.health.StepsProvider
import com.blizzardcaron.freeolleefaces.location.Coords
import kotlinx.coroutines.launch

/**
 * VM callbacks [rememberPermissionsCoordinator] needs, bundled to keep the composable's parameter
 * list short. Injected as lambdas (not the VM itself) to keep the coordinator decoupled and
 * testable independent of [AppViewModel].
 */
data class PermissionsCallbacks(
    val backgroundActive: () -> Boolean,
    val onBluetoothGranted: () -> Unit,
    val onBluetoothDenied: () -> Unit,
    val setLocating: (Boolean) -> Unit,
    val onLocationFetched: (Double, Double) -> Unit,
    val onLocationFailed: (String?) -> Unit,
    val onLocationDenied: () -> Unit,
    val fetchLocation: suspend () -> Result<Coords>,
    val refreshSteps: (push: Boolean) -> Unit,
    val activeIsSteps: () -> Boolean,
    val onPartialHealthGrant: () -> Unit,
    val healthAvailability: () -> StepsProvider.Availability,
    val onHealthUpdateRequired: () -> Unit,
    val onHealthUnavailable: () -> Unit,
)

/**
 * Android permission machinery extracted from [MainActivity]'s `AppRoot`: the
 * bluetooth/notification/location/health permission launchers plus the request helpers that drive
 * them. Moved verbatim — the only de-duplication is collapsing the fetch-location flow (shared by
 * the location launcher's grant callback and [useMyLocation]) into [requestLocationFix].
 */
class PermissionsCoordinator(
    private val hasPermission: (String) -> Boolean,
    private val onBluetoothGranted: () -> Unit,
    private val onBluetoothDenied: () -> Unit,
    private val launchBluetoothPermission: () -> Unit,
    private val launchLocationPermissions: () -> Unit,
    private val launchHealthPermission: () -> Unit,
    private val setLocating: (Boolean) -> Unit,
    private val fetchLocationFix: () -> Unit,
    private val healthAvailability: () -> StepsProvider.Availability,
    private val onHealthUpdateRequired: () -> Unit,
    private val onHealthUnavailable: () -> Unit,
) {
    fun selectWatch() {
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            onBluetoothGranted()
        } else {
            launchBluetoothPermission()
        }
    }

    fun useMyLocation() {
        val hasAny = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (hasAny) {
            setLocating(true)
            fetchLocationFix()
        } else {
            launchLocationPermissions()
        }
    }

    fun requestHealth() {
        when (healthAvailability()) {
            StepsProvider.Availability.AVAILABLE -> launchHealthPermission()
            StepsProvider.Availability.UPDATE_REQUIRED -> onHealthUpdateRequired()
            StepsProvider.Availability.UNAVAILABLE -> onHealthUnavailable()
        }
    }
}

@Composable
fun rememberPermissionsCoordinator(callbacks: PermissionsCallbacks): PermissionsCoordinator {
    val context = LocalContext.current
    val kotlinScope = rememberCoroutineScope()

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun requestLocationFix() {
        kotlinScope.launch {
            callbacks.fetchLocation()
                .onSuccess { coords -> callbacks.onLocationFetched(coords.lat, coords.lng) }
                .onFailure { err -> callbacks.onLocationFailed(err.message) }
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) callbacks.onBluetoothGranted() else callbacks.onBluetoothDenied()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — errors still record in-app either way */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && callbacks.backgroundActive() &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            callbacks.setLocating(true)
            requestLocationFix()
        } else {
            callbacks.onLocationDenied()
        }
    }

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        // The read permission alone is enough to show steps; background read just lets the
        // worker keep updating while the app is closed.
        callbacks.refreshSteps(callbacks.activeIsSteps())
        if (!granted.containsAll(AndroidStepsProvider.PERMISSIONS)) {
            callbacks.onPartialHealthGrant()
        }
    }

    return PermissionsCoordinator(
        hasPermission = ::hasPermission,
        onBluetoothGranted = callbacks.onBluetoothGranted,
        onBluetoothDenied = callbacks.onBluetoothDenied,
        launchBluetoothPermission = { btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
        launchLocationPermissions = {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        },
        launchHealthPermission = { healthPermissionLauncher.launch(AndroidStepsProvider.PERMISSIONS) },
        setLocating = callbacks.setLocating,
        fetchLocationFix = ::requestLocationFix,
        healthAvailability = callbacks.healthAvailability,
        onHealthUpdateRequired = callbacks.onHealthUpdateRequired,
        onHealthUnavailable = callbacks.onHealthUnavailable,
    )
}
