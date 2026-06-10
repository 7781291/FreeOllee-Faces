package com.blizzardcaron.freeolleefaces.fakes

import com.blizzardcaron.freeolleefaces.auto.Scheduler
import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.health.StepsProvider
import com.blizzardcaron.freeolleefaces.location.Coords
import com.blizzardcaron.freeolleefaces.location.LocationProvider
import com.blizzardcaron.freeolleefaces.notifications.NotificationAccessChecker
import kotlinx.coroutines.CompletableDeferred

// ---------------------------------------------------------------------------
// FakeBleClient
// ---------------------------------------------------------------------------

/**
 * Hand-written fake BleClient. All three send variants record into [callLog] and return
 * [sendResult]. The gate deferred, if set, suspends every send until completed — used by
 * the in-flight guard test.
 *
 * For cross-fake ordering assertions (e.g. reschedule-before-send), pass the SAME list
 * instance here and to [FakeScheduler]; separately constructed logs can't be compared.
 */
class FakeBleClient(
    private val callLog: MutableList<String> = mutableListOf(),
    var sendResult: Result<Unit> = Result.success(Unit),
    /** When non-null, each call suspends until [gate].await() resolves. */
    var gate: CompletableDeferred<Unit>? = null,
) : BleClient {

    override suspend fun send(deviceAddress: String, value: String): Result<Unit> {
        gate?.await()
        callLog += "ble.send($deviceAddress,$value)"
        return sendResult
    }

    override suspend fun send(deviceAddress: String, value: String, target: Int): Result<Unit> {
        gate?.await()
        callLog += "ble.send($deviceAddress,$value,target=$target)"
        return sendResult
    }

    override suspend fun sendPacket(deviceAddress: String, packet: ByteArray): Result<Unit> {
        gate?.await()
        callLog += "ble.sendPacket($deviceAddress)"
        return sendResult
    }
}

// ---------------------------------------------------------------------------
// FakeStepsProvider
// ---------------------------------------------------------------------------

class FakeStepsProvider(
    var availability: StepsProvider.Availability = StepsProvider.Availability.AVAILABLE,
    var readPermission: Boolean = true,
    var stepsResult: Result<Long> = Result.success(0L),
) : StepsProvider {

    override fun availability(): StepsProvider.Availability = availability

    override suspend fun hasReadPermission(): Boolean = readPermission

    override suspend fun todaySteps(): Result<Long> = stepsResult
}

// ---------------------------------------------------------------------------
// FakeLocationProvider
// ---------------------------------------------------------------------------

class FakeLocationProvider(
    var fetchResult: Result<Coords> = Result.failure(IllegalStateException("no location")),
) : LocationProvider {

    override suspend fun fetch(timeoutMs: Long): Result<Coords> = fetchResult
}

// ---------------------------------------------------------------------------
// FakeNotificationAccessChecker
// ---------------------------------------------------------------------------

class FakeNotificationAccessChecker(
    var granted: Boolean = false,
) : NotificationAccessChecker {

    override fun isGranted(): Boolean = granted
}

// ---------------------------------------------------------------------------
// FakeScheduler
// ---------------------------------------------------------------------------

/**
 * Records "scheduler.reschedule" into the shared [callLog] so tests can assert ordering
 * relative to BLE sends.
 */
class FakeScheduler(
    private val callLog: MutableList<String> = mutableListOf(),
) : Scheduler {

    override fun reschedule() {
        callLog += "scheduler.reschedule"
    }
}
