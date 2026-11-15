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
import ch.vautherin.px4_sih.SitlService
import io.mavsdk.kotlin.System as MavsdkSystem
import io.mavsdk.kotlin.plugins.action.Action
import io.mavsdk.kotlin.plugins.telemetry.Telemetry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeout

object Autopilot {
    const val id = "px4"
    const val cameraVendor = "PX4"
    const val cameraModel = "SIH camera"
    val serviceClass = SitlService::class.java
    val monitorConnection = "udpin://127.0.0.1:${SitlService.MONITOR_PORT}"

    fun startIntent(context: Context, settings: SitlConfig.Settings) =
        Intent(context, serviceClass)
            .setAction(SitlService.ACTION_START)
            .putExtra(SitlService.EXTRA_MODEL, settings.model)
            .putExtra(SitlService.EXTRA_SPEEDUP, settings.speedup)
            .putExtra(SitlService.EXTRA_MAVLINK_PORT, settings.mavlinkPort)

    suspend fun takeoff(system: MavsdkSystem, state: () -> VehicleState) {
        // Like PX4's commander takeoff: select takeoff before arming.
        val takeoff = system.action.takeoff()
        check(takeoff == Action.Result.SUCCESS) { "takeoff mode: $takeoff" }
        withTimeout(MODE_TIMEOUT_MS) {
            system.telemetry.subscribeFlightMode()
                .onStart { emit(system.telemetry.flightMode()) }
                .first { it == Telemetry.FlightMode.TAKEOFF }
        }
        val result = system.action.arm()
        check(result == Action.Result.SUCCESS) { "arming: $result" }
    }

    suspend fun land(system: MavsdkSystem, state: () -> VehicleState) {
        val result = system.action.land()
        check(result == Action.Result.SUCCESS) { "landing: $result" }
    }

    suspend fun watchMode(system: MavsdkSystem, update: suspend (Int) -> Unit) = Unit

    private const val MODE_TIMEOUT_MS = 3000L
}
