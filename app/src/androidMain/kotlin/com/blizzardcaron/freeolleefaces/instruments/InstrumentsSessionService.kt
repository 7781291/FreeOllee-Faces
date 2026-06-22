package com.blizzardcaron.freeolleefaces.instruments

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blizzardcaron.freeolleefaces.R
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.TemperatureReadback
import com.blizzardcaron.freeolleefaces.location.AndroidLocationProvider
import com.blizzardcaron.freeolleefaces.location.AndroidLocationStream
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import com.blizzardcaron.freeolleefaces.weather.RetryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts an [InstrumentsSessionEngine] for the lifetime of an Instruments
 * session. Owns the GPS-collect loop, the pressure source (barometer sensor, OpenMeteo fallback),
 * the onboard-temperature read loop, and the 1s push ticker. Mirrors [ActivitySessionService] but
 * records no track.
 */
class InstrumentsSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var ble: BleClient
    private lateinit var engine: InstrumentsSessionEngine
    private var loops: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(appSettings(this))
        ble = AndroidBleClient(this)
        engine = InstrumentsSessionEngine(
            ble = ble,
            unitProvider = { prefs.activityUnit },
            tempUnitProvider = { prefs.tempUnit },
            watchAddress = { prefs.watchAddress },
        )
        engine.state
            .onEach {
                InstrumentsSessionHost.mutableState.value = it
                updateNotification(it)
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSession()
            ACTION_CYCLE -> engine.cycleInstrument()
            ACTION_SET_UNIT -> engine.onUnitChanged()
        }
        return START_STICKY
    }

    // Location permission is gated upstream by InstrumentsController.onStart(); compass/altitude
    // simply render "---" until a fix arrives, so a missing grant degrades rather than crashes.
    @SuppressLint("MissingPermission")
    private fun startSession() {
        if (InstrumentsSessionHost.isRunning) return
        InstrumentsSessionHost.isRunning = true
        startForegroundCompat()
        loops = scope.launch {
            engine.start()
            val location = launch {
                AndroidLocationStream(this@InstrumentsSessionService).stream()
                    .onEach { engine.ingestLocation(it) }
                    .launchIn(this)
            }
            val pressure = launch { drivePressure() }
            val temp = launch { driveTemperature() }
            val ticker = launch {
                while (isActive) {
                    engine.tick(System.currentTimeMillis())
                    delay(TICK_MS)
                }
            }
            location.join()
            pressure.join()
            temp.join()
            ticker.join()
        }
    }

    // Prefer the onboard barometer; if it never emits within the probe window, fall back to a
    // periodic OpenMeteo surface-pressure lookup keyed off the current location.
    private suspend fun drivePressure() = coroutineScope {
        var sawSensor = false
        val sensor = launch {
            AndroidPressureStream(this@InstrumentsSessionService).stream().collect { hpa ->
                sawSensor = true
                engine.ingestPressure(hpa, PressureSource.SENSOR)
            }
        }
        delay(PRESSURE_PROBE_MS)
        if (sawSensor) {
            sensor.join()
            return@coroutineScope
        }
        sensor.cancel()
        val provider = AndroidLocationProvider(this@InstrumentsSessionService)
        while (isActive) {
            val hpa = provider.fetch().getOrNull()?.let {
                OpenMeteoClient.currentPressureHpa(it.lat, it.lng, RetryPolicy.Preview).getOrNull()
            }
            engine.ingestPressure(hpa, if (hpa != null) PressureSource.NETWORK else PressureSource.NONE)
            delay(PRESSURE_NETWORK_REFRESH_MS)
        }
    }

    private suspend fun driveTemperature() = coroutineScope {
        while (isActive) {
            prefs.watchAddress?.let { engine.ingestTemp(TemperatureReadback.read(ble, it)) }
            delay(TEMP_READ_INTERVAL_MS)
        }
    }

    private fun stopSession() {
        scope.launch {
            loops?.cancel()
            engine.stop()
            InstrumentsSessionHost.isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundCompat() {
        val n = baseNotification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(state: InstrumentsState) {
        if (!InstrumentsSessionHost.isRunning) return
        val text = "${state.selectedInstrument.name.lowercase()} · ${state.lastPushText ?: "…"}"
        ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?.notify(NOTIF_ID, baseNotification(text))
    }

    private fun baseNotification(text: String): Notification {
        ensureChannel()
        val stopIntent = android.app.PendingIntent.getService(
            this,
            0,
            Intent(this, InstrumentsSessionService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Instruments active")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun ensureChannel() {
        val mgr = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Instruments", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onDestroy() {
        InstrumentsSessionHost.isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 4202
        private const val CHANNEL_ID = "instruments_session"
        private const val TICK_MS = 1000L
        private const val PRESSURE_PROBE_MS = 3000L
        private const val PRESSURE_NETWORK_REFRESH_MS = 600_000L // 10 min — rate-limit/battery
        private const val TEMP_READ_INTERVAL_MS = 45_000L
        const val ACTION_START = "com.blizzardcaron.freeolleefaces.instruments.START"
        const val ACTION_STOP = "com.blizzardcaron.freeolleefaces.instruments.STOP"
        const val ACTION_CYCLE = "com.blizzardcaron.freeolleefaces.instruments.CYCLE"
        const val ACTION_SET_UNIT = "com.blizzardcaron.freeolleefaces.instruments.SET_UNIT"

        private fun send(context: Context, action: String, foreground: Boolean) {
            val intent = Intent(context, InstrumentsSessionService::class.java).setAction(action)
            if (foreground) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(context: Context) = send(context, ACTION_START, foreground = true)
        fun stop(context: Context) = send(context, ACTION_STOP, foreground = false)
        fun cycle(context: Context) = send(context, ACTION_CYCLE, foreground = false)
        fun setUnit(context: Context) = send(context, ACTION_SET_UNIT, foreground = false)
    }
}
