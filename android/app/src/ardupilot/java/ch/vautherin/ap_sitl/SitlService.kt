/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.ap_sitl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import ch.vautherin.sih.SimCamera
import ch.vautherin.sih.SimLock
import ch.vautherin.sih.SitlConfig
import java.io.File

/**
 * Hosts ArduPilot SITL in the ":sitl" process, as a foreground service so
 * that it keeps running while another app is in front.
 *
 * Any app may start it with [ACTION_START] and stop it with [ACTION_STOP].
 * The simulator cannot be shut down from the inside, so stopping the service
 * kills that process; the next start is a fresh boot of the vehicle.
 */
class SitlService : Service() {
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val speedup = intent.extra(EXTRA_SPEEDUP) ?: "1"
        val mavlinkPort = intent.extra(EXTRA_MAVLINK_PORT) ?: DEFAULT_MAVLINK_PORT.toString()

        // owed to the system within seconds of startForegroundService(), whatever the action
        val notification = notification("MAVLink on UDP port $mavlinkPort")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) {
            // already running, with the options of the first start
            return START_NOT_STICKY
        }

        val vehicleId = intent.extra(EXTRA_VEHICLE) ?: SitlConfig.Vehicle.COPTER.id
        val vehicle = SitlConfig.Vehicle.fromId(vehicleId)
        val model = intent.extra(EXTRA_MODEL) ?: vehicle?.defaultModel
        if (vehicle == null || model == null || !SitlConfig.isValidModel(model)) {
            Log.e(TAG, "unsupported vehicle \"$vehicleId\" or model \"$model\"")
            stopSelf()
            return START_NOT_STICKY
        }
        started = true

        val workDir = SitlConfig.workDir(filesDir, vehicle, model).apply { mkdirs() }
        SitlConfig.currentFile(filesDir).writeText("${vehicle.id} $model $speedup $mavlinkPort")
        val args = mutableListOf(
            "ardu${vehicle.id}",
            "--model", model,
            "--speedup", speedup,
            // for the app under test
            "--serial0", "udpclient:127.0.0.1:$mavlinkPort",
            // for this app's own status display, and for adb forward
            "--serial1", "tcp:$MONITOR_PORT",
            // for the camera, see SimCamera
            "--serial2", "udpclient:127.0.0.1:${SimCamera.PORT}",
            // on top of the frame's own defaults
            "--defaults", extraDefaults().absolutePath,
        )
        intent.extra(EXTRA_HOME)?.let { args += listOf("--home", it) }
        if (intent?.getBooleanExtra(EXTRA_WIPE, false) == true) {
            args += "-w"
        }

        // the vehicle's main loop runs on this thread, give it a desktop-sized stack
        Thread(null, {
            // first of all, so that nothing of this simulator runs next to another one
            if (!SimLock.acquire(LOCK_NAME)) {
                Log.e(TAG, "another simulator is running and does not stop")
                Process.killProcess(Process.myPid())
            }
            // binds its port at once and finds the vehicle when it comes up
            SimCamera().start()

            val ret = SitlNative.run(vehicle.library, workDir.absolutePath,
                SitlConfig.logFile(filesDir).absolutePath, args.toTypedArray())
            Log.e(TAG, "SITL returned $ret")
            Process.killProcess(Process.myPid())
        }, "sitl-main", 16L * 1024 * 1024).start()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Process.killProcess(Process.myPid())
    }

    /**
     * Turns on the gimbal, which is off by default. ArduPilot is its own gimbal
     * manager, and with the servo driver the gimbal is simply where it was
     * told to go. The two outputs are ones no frame offered here uses.
     *
     * Copter streams nothing unless asked, and an app that only listens does
     * not ask. So SERIAL0 and SERIAL1 get the rates a ground station would
     * request, which include the gimbal's attitude (EXTRA3), by which it is
     * found. The camera needs to know where the vehicle is. MAV1 to MAV3 are
     * SERIAL0 to SERIAL2.
     */
    private fun extraDefaults(): File {
        val streams = listOf(
            "RAW_SENS" to 2, "EXT_STAT" to 2, "RC_CHAN" to 2, "POSITION" to 5,
            "EXTRA1" to 10, "EXTRA2" to 5, "EXTRA3" to 5,
        )
        val lines = listOf(
            "MNT1_TYPE 1",
            "SERVO13_FUNCTION 7",
            "SERVO14_FUNCTION 6",
            "MAV3_POSITION 4",
            "MAV3_EXTRA1 4",
        ) + listOf(1, 2).flatMap { link -> streams.map { (stream, hz) -> "MAV${link}_$stream $hz" } }
        val file = File(SitlConfig.rootDir(filesDir), "extra.parm")
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        return file
    }

    /** Extras may arrive as strings or numbers depending on the caller. */
    @Suppress("DEPRECATION")
    private fun Intent?.extra(name: String): String? = this?.extras?.get(name)?.toString()

    private fun notification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Simulator", NotificationManager.IMPORTANCE_LOW))

        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0,
            Intent(this, SitlService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("ArduPilot SITL is running")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "SitlService"
        private const val CHANNEL_ID = "sitl"
        private const val NOTIFICATION_ID = 1
        /** What this simulator answers a status request on [SimLock.PORT] with. */
        private const val LOCK_NAME = "ardupilot"

        const val ACTION_START = "ch.vautherin.ap_sitl.action.START"
        const val ACTION_STOP = "ch.vautherin.ap_sitl.action.STOP"

        /** Simulation speed relative to real time, default 1. */
        const val EXTRA_SPEEDUP = "speedup"
        /** Loopback UDP port the vehicle sends MAVLink to, default [DEFAULT_MAVLINK_PORT]. */
        const val EXTRA_MAVLINK_PORT = "mavlink_port"
        /** Firmware, "copter" (default) or "plane". Plane includes the VTOLs. */
        const val EXTRA_VEHICLE = "vehicle"
        /** Frame model, default "quad" for copter and "plane" for plane. */
        const val EXTRA_MODEL = "model"
        /** Start location as "lat,lon,alt,heading". */
        const val EXTRA_HOME = "home"
        /** Boolean, erase stored parameters and missions before starting. */
        const val EXTRA_WIPE = "wipe"

        const val DEFAULT_MAVLINK_PORT = 14540
        const val MONITOR_PORT = 5762
    }
}
