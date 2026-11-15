/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.sih

import android.content.res.AssetManager
import ch.vautherin.px4_sih.SitlService
import java.io.File

/** What the simulator can run, and where it keeps its files. */
object SitlConfig {
    /**
     * A SIH vehicle. [autostart] is the number of its airframe file in
     * etc/init.d-posix/airframes, named <autostart>_sihsim_<model>.
     */
    class Model(val id: String, val label: String, val autostart: Int, val isPlane: Boolean)

    val models = listOf(
        Model("quadx", "Quadcopter", 10040, isPlane = false),
        Model("hex", "Hexacopter", 10044, isPlane = false),
        Model("airplane", "Fixed-wing", 10041, isPlane = true),
        Model("standard_vtol", "Standard VTOL", 10043, isPlane = true),
        Model("xvert", "Tailsitter", 10042, isPlane = true),
    )

    fun model(id: String) = models.firstOrNull { it.id == id }

    data class Settings(
        val model: String = models.first().id,
        val speedup: String = "1",
        val mavlinkPort: String = SitlService.DEFAULT_MAVLINK_PORT.toString(),
    )

    val presets get() = models

    fun selectedPreset(settings: Settings) = model(settings.model)

    fun selectPreset(settings: Settings, preset: Model) = settings.copy(model = preset.id)

    fun isPlane(settings: Settings) = model(settings.model)?.isPlane == true

    fun canLand(settings: Settings) = model(settings.model) != null

    fun isValidSpeed(value: String) = value.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true

    fun isValidPort(value: String) = value.toIntOrNull()?.let { it in 1..65535 } == true

    fun readSettings(filesDir: File): Settings? = try {
        val fields = currentFile(filesDir).readText().trim().split(' ')
        if (model(fields[0]) != null) {
            Settings(
                fields[0], fields.getOrNull(1) ?: "1",
                fields.getOrNull(2) ?: SitlService.DEFAULT_MAVLINK_PORT.toString(),
            )
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    fun rootDir(filesDir: File) = File(filesDir, "px4")

    /** The startup scripts and airframes, unpacked from the APK. */
    fun dataDir(filesDir: File) = File(rootDir(filesDir), "etc")

    /** Stands in for the directory of the px4 program, which is where px4-alias.sh is looked for. */
    fun binDir(filesDir: File) = File(rootDir(filesDir), "bin")

    /**
     * Each model keeps its own stored parameters, missions and logs, so
     * switching between them never starts one with another's setup.
     */
    fun workDir(filesDir: File, model: Model) = File(rootDir(filesDir), "rootfs-${model.id}")

    /** Console output of the current or last run. */
    fun logFile(filesDir: File) = File(rootDir(filesDir), "px4.log")

    /** "model speedup mavlink_port" of the current or last run. */
    fun currentFile(filesDir: File) = File(rootDir(filesDir), "current")

    /** Replaces [target] with the asset directory [path]. */
    fun unpack(assets: AssetManager, path: String, target: File) {
        target.deleteRecursively()
        copy(assets, path, target)
    }

    private fun copy(assets: AssetManager, path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            children.forEach { copy(assets, "$path/$it", File(target, it)) }
        }
    }
}
