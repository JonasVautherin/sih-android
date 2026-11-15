/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.sih

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.snapshotFlow
import ch.vautherin.ap_sitl.Flight
import ch.vautherin.ap_sitl.SitlService
import io.mavsdk.kotlin.System as MavsdkSystem
import io.mavsdk.kotlin.plugins.action.Action
import io.mavsdk.kotlin.plugins.mavlink_direct.MavlinkDirect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

object Autopilot {
    const val id = "ardupilot"
    const val cameraVendor = "ArduPilot"
    const val cameraModel = "SITL camera"
    val serviceClass = SitlService::class.java
    val monitorConnection = "tcp://127.0.0.1:${SitlService.MONITOR_PORT}"

    fun startIntent(context: Context, settings: SitlConfig.Settings) =
        Intent(context, serviceClass)
            .setAction(SitlService.ACTION_START)
            .putExtra(SitlService.EXTRA_VEHICLE, settings.vehicle.id)
            .putExtra(SitlService.EXTRA_MODEL, settings.model)
            .putExtra(SitlService.EXTRA_SPEEDUP, settings.speedup)
            .putExtra(SitlService.EXTRA_MAVLINK_PORT, settings.mavlinkPort)

    suspend fun takeoff(system: MavsdkSystem, state: () -> VehicleState) {
        val settings = state().settings
        val flight = Flight.of(settings.vehicle.id, settings.model)
        setMode(system, flight.takeoffMode, state)
        val result = system.action.arm()
        check(result == Action.Result.SUCCESS) { "arming: $result" }
        if (flight.takeoffCommand) {
            // Send immediately after the arm ACK, before accelerated SITL auto-disarms.
            sendCommand(system, CMD_NAV_TAKEOFF, 0f, 0f, 0f, 0f, 0f, 0f, 20f)
        }
    }

    suspend fun land(system: MavsdkSystem, state: () -> VehicleState) {
        val settings = state().settings
        val flight = Flight.of(settings.vehicle.id, settings.model)
        val mode = checkNotNull(flight.landMode) { "fixed-wing landing requires a landing mission; use RTL" }
        setMode(system, mode, state)
    }

    suspend fun watchMode(system: MavsdkSystem, update: suspend (Int) -> Unit) {
        // Keep ArduPilot's exact custom mode rather than MAVSDK's generic mode enum.
        system.mavlinkDirect.subscribeMessage("HEARTBEAT").collect { message ->
            if (message.componentId == AUTOPILOT_COMPONENT_ID) {
                update(JSONObject(message.fieldsJson).getInt("custom_mode"))
            }
        }
    }

    private suspend fun setMode(system: MavsdkSystem, mode: Int, state: () -> VehicleState) {
        sendCommand(system, CMD_DO_SET_MODE, 1f, mode.toFloat())
        withTimeout(MODE_TIMEOUT_MS) {
            snapshotFlow { state().mode }.first { it == mode }
        }
    }

    private suspend fun sendCommand(system: MavsdkSystem, command: Int, vararg params: Float) {
        val fields = JSONObject()
            .put("command", command)
            .put("target_system", system.getSystemId())
            .put("target_component", AUTOPILOT_COMPONENT_ID)
            .put("confirmation", 0)
        repeat(7) { fields.put("param${it + 1}", params.getOrElse(it) { 0f }.toDouble()) }
        val ack = system.mavlinkDirect.sendCommand(MavlinkDirect.MavlinkMessage(
            messageName = "COMMAND_LONG",
            systemId = 0,
            componentId = 0,
            targetSystemId = system.getSystemId(),
            targetComponentId = AUTOPILOT_COMPONENT_ID,
            fieldsJson = fields.toString(),
        )).getOrThrow()
        val result = JSONObject(ack.fieldsJson).getInt("result")
        check(result == MAV_RESULT_ACCEPTED) { "command $command rejected (MAV_RESULT $result)" }
    }

    private const val MODE_TIMEOUT_MS = 3000L
    private const val AUTOPILOT_COMPONENT_ID = 1
    private const val MAV_RESULT_ACCEPTED = 0
    private const val CMD_NAV_TAKEOFF = 22
    private const val CMD_DO_SET_MODE = 176
}
