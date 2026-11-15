/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.sih

import ch.vautherin.ap_sitl.Flight
import ch.vautherin.ap_sitl.SitlService

import java.io.File

/** What the simulator can run, and where it keeps its files. */
object SitlConfig {
    /** A vehicle firmware, each built as its own shared library. */
    enum class Vehicle(val id: String, val library: String, val defaultModel: String) {
        COPTER("copter", "libarducopter.so", "quad"),
        PLANE("plane", "libarduplane.so", "plane");

        companion object {
            fun fromId(id: String) = entries.firstOrNull { it.id == id }
        }
    }

    /** The choices offered in the UI. Callers of the service may pass any model SITL knows. */
    class Preset(val label: String, val vehicle: Vehicle, val model: String)

    val presets = listOf(
        Preset("Quadcopter", Vehicle.COPTER, "quad"),
        Preset("Fixed-wing", Vehicle.PLANE, "plane"),
        Preset("QuadPlane", Vehicle.PLANE, "quadplane"),
        Preset("Tiltrotor", Vehicle.PLANE, "quadplane-tilt"),
        Preset("Tailsitter", Vehicle.PLANE, "plane-tailsitter"),
    )

    data class Settings(
        val vehicle: Vehicle = Vehicle.COPTER,
        val model: String = Vehicle.COPTER.defaultModel,
        val speedup: String = "1",
        val mavlinkPort: String = SitlService.DEFAULT_MAVLINK_PORT.toString(),
    )

    fun selectedPreset(settings: Settings) =
        presets.firstOrNull { it.vehicle == settings.vehicle && it.model == settings.model }

    fun selectPreset(settings: Settings, preset: Preset) = settings.copy(vehicle = preset.vehicle, model = preset.model)

    fun isPlane(settings: Settings) = settings.vehicle == Vehicle.PLANE

    fun canLand(settings: Settings) = Flight.of(settings.vehicle.id, settings.model).landMode != null

    fun isValidSpeed(value: String) = value.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true

    fun isValidPort(value: String) = value.toIntOrNull()?.let { it in 1..65535 } == true

    fun readSettings(filesDir: File): Settings? = try {
        val fields = currentFile(filesDir).readText().trim().split(' ')
        val vehicle = Vehicle.fromId(fields[0])
        if (vehicle != null && fields.size >= 2 && isValidModel(fields[1])) {
            Settings(
                vehicle, fields[1],
                fields.getOrNull(2) ?: "1",
                fields.getOrNull(3) ?: SitlService.DEFAULT_MAVLINK_PORT.toString(),
            )
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /** Whether a Plane model can hover, which decides how it takes off and lands. */
    fun isVtol(model: String) = model.startsWith("quadplane") || model.contains("tailsitter")

    private val modelPattern = Regex("[A-Za-z0-9_+-]+")

    fun isValidModel(model: String) = modelPattern.matches(model)

    fun rootDir(filesDir: File) = File(filesDir, "sitl")

    /**
     * Each vehicle and model keeps its own stored parameters, missions and
     * logs, so switching between them never starts one with another's setup.
     */
    fun workDir(filesDir: File, vehicle: Vehicle, model: String) =
        File(rootDir(filesDir), "${vehicle.id}-$model")

    /** Console output of the current or last run. */
    fun logFile(filesDir: File) = File(rootDir(filesDir), "sitl.log")

    /** "vehicle model speedup mavlink_port" of the current or last run. */
    fun currentFile(filesDir: File) = File(rootDir(filesDir), "current")
}
