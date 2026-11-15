/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.sih

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.mavsdk.kotlin.ComponentType
import io.mavsdk.kotlin.Configuration
import io.mavsdk.kotlin.Mavsdk
import io.mavsdk.kotlin.System as MavsdkSystem
import io.mavsdk.kotlin.plugins.action.Action
import io.mavsdk.kotlin.plugins.telemetry.Telemetry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** What the screen shows about the simulated vehicle. */
data class VehicleState(
    val connected: Boolean = false,
    val settings: SitlConfig.Settings = SitlConfig.Settings(),
    val mode: Int = 0,
    val armed: Boolean = false,
    val health: Telemetry.Health? = null,
    val commandBusy: Boolean = false,
    val commandError: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val headingDeg: Float = 0f,
    val relativeAltM: Float = 0f,
    val simSpeed: Float = 0f,
    val statusText: String = "",
) {
    val readyForTakeoff: Boolean
        get() = connected && !armed && health?.let {
            it.isArmable && it.isGyrometerCalibrationOk && it.isAccelerometerCalibrationOk &&
                it.isMagnetometerCalibrationOk && it.isLocalPositionOk &&
                it.isGlobalPositionOk && it.isHomePositionOk
        } == true

    val readinessText: String
        get() {
            if (!connected) return "Waiting for vehicle connection"
            if (armed) return "Armed"
            val health = health ?: return "Waiting for health telemetry"
            val missing = buildList {
                if (!health.isGyrometerCalibrationOk) add("gyroscope")
                if (!health.isAccelerometerCalibrationOk) add("accelerometer")
                if (!health.isMagnetometerCalibrationOk) add("compass")
                if (!health.isGlobalPositionOk) add("GPS position estimate")
                else if (!health.isLocalPositionOk) add("local position estimate")
                if (!health.isHomePositionOk) add("home position")
                if (!health.isArmable) add("pre-arm checks")
            }
            return if (missing.isEmpty()) "Ready for takeoff" else "Waiting for: ${missing.joinToString()}"
        }
}

/** Watches the flavor's monitor link, leaving the main link free for other apps. */
class VehicleMonitor(private val filesDir: File) {
    var state by mutableStateOf(VehicleState())
        private set

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private var session: Session? = null

    private class Session(val system: MavsdkSystem, val scope: CoroutineScope) {
        var commandJob: Job? = null
    }

    fun start() {
        val previous = monitorJob
        if (previous?.isActive == true) return
        monitorJob = scope.launch {
            // Even a cancelled restart must wait for the preceding SDK instance to close.
            withContext(NonCancellable) { previous?.join() }
            connectLoop()
        }
    }

    fun stop() {
        session = null
        monitorJob?.cancel()
        state = VehicleState()
    }

    fun takeoff() = command("Takeoff") { system ->
        check(state.readyForTakeoff) { state.readinessText }
        Autopilot.takeoff(system) { state }
    }

    fun land() = command("Landing") { system ->
        Autopilot.land(system) { state }
    }

    fun returnToLaunch() = command("RTL") { system ->
        val result = system.action.returnToLaunch()
        check(result == Action.Result.SUCCESS) { "RTL: $result" }
    }

    private fun command(name: String, action: suspend (MavsdkSystem) -> Unit) {
        val active = session
        if (active == null || !state.connected) {
            state = state.copy(commandError = "$name failed: no vehicle connection")
            return
        }
        if (active.commandJob?.isActive == true) {
            return
        }
        state = state.copy(commandBusy = true, commandError = "")
        active.commandJob = active.scope.launch {
            try {
                withTimeout(COMMAND_TIMEOUT_MS) { action(active.system) }
            } catch (e: TimeoutCancellationException) {
                state = state.copy(commandError = "$name failed: timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(commandError = "$name failed: ${e.message}")
            } finally {
                if (session === active) state = state.copy(commandBusy = false)
                if (state.commandError.isNotEmpty()) Log.w(TAG, state.commandError)
            }
        }
    }

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            val mavsdk = Configuration.createWithComponentType(ComponentType.GROUND_STATION).use {
                it.systemId = 255
                it.componentId = 190
                Mavsdk(it)
            }
            try {
                withContext(Dispatchers.IO) {
                    mavsdk.addAnyConnection(Autopilot.monitorConnection).getOrThrow()
                }
                // Bounded discovery keeps shutdown responsive while the simulator starts.
                var system: MavsdkSystem? = null
                while (system == null) {
                    currentCoroutineContext().ensureActive()
                    system = withContext(Dispatchers.IO) { mavsdk.firstAutopilot(1.0) }
                }
                system.subscribeIsConnected()
                    .onStart { emit(system.isConnected()) }
                    .distinctUntilChanged()
                    .collectLatest { connected ->
                        state = VehicleState()
                        if (connected) watchVehicle(system)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = VehicleState(statusText = "Monitor failed: ${e.message}")
            } finally {
                session = null
                withContext(NonCancellable + Dispatchers.IO) { mavsdk.close() }
            }
            delay(500)
        }
    }

    private suspend fun watchVehicle(system: MavsdkSystem): Unit = coroutineScope {
        val telemetry = system.telemetry
        val direct = system.mavlinkDirect
        val current = withContext(Dispatchers.IO) { current() }
        val active = Session(system, this)
        session = active
        state = VehicleState(
            connected = true, settings = current,
            armed = telemetry.armed(), health = telemetry.health(),
        )
        try {
            launch {
                telemetry.subscribePosition().collect { position ->
                    val hasPosition = position.latitudeDeg.isFinite() && position.longitudeDeg.isFinite() &&
                        (position.latitudeDeg != 0.0 || position.longitudeDeg != 0.0)
                    state = state.copy(
                        latitude = if (hasPosition) position.latitudeDeg else null,
                        longitude = if (hasPosition) position.longitudeDeg else null,
                        relativeAltM = position.relativeAltitudeM,
                    )
                }
            }
            launch {
                telemetry.subscribeHeading().collect {
                    if (it.headingDeg.isFinite()) state = state.copy(headingDeg = it.headingDeg.toFloat())
                }
            }
            launch {
                telemetry.subscribeArmed().collect { state = state.copy(armed = it) }
            }
            launch {
                telemetry.subscribeHealth().collect { state = state.copy(health = it) }
            }
            launch {
                telemetry.subscribeStatusText().collect { state = state.copy(statusText = it.text) }
            }
            launch {
                Autopilot.watchMode(system) { mode ->
                    val current = withContext(Dispatchers.IO) { current() }
                    state = state.copy(settings = current, mode = mode)
                }
            }
            launch {
                var simRefBootMs = 0L
                var simRefWallMs = 0L
                // Typed Position omits the boot timestamp needed to measure simulation speed.
                direct.subscribeMessage("GLOBAL_POSITION_INT").collect { message ->
                    if (message.componentId != AUTOPILOT_COMPONENT_ID) return@collect
                    val bootMs = JSONObject(message.fieldsJson).getLong("time_boot_ms")
                    val now = SystemClock.elapsedRealtime()
                    if (simRefWallMs == 0L || bootMs < simRefBootMs) {
                        simRefBootMs = bootMs
                        simRefWallMs = now
                        val current = withContext(Dispatchers.IO) { current() }
                        state = state.copy(simSpeed = 0f, settings = current)
                    } else if (now - simRefWallMs >= 2000) {
                        state = state.copy(simSpeed = (bootMs - simRefBootMs).toFloat() / (now - simRefWallMs))
                        simRefBootMs = bootMs
                        simRefWallMs = now
                    }
                }
            }
            launch {
                val result = telemetry.setRatePosition(POSITION_RATE_HZ)
                if (result != Telemetry.Result.SUCCESS) {
                    state = state.copy(statusText = "Position rate request failed: $result")
                }
            }
            awaitCancellation()
        } finally {
            if (session === active) session = null
        }
    }

    private companion object {
        const val TAG = "VehicleMonitor"
        const val POSITION_RATE_HZ = 10.0
        const val COMMAND_TIMEOUT_MS = 10_000L
        const val AUTOPILOT_COMPONENT_ID = 1
    }

    private fun current() = SitlConfig.readSettings(filesDir) ?: SitlConfig.Settings()
}
