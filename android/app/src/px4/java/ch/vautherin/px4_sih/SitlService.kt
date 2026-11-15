/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.px4_sih

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
import java.util.Locale

/**
 * Hosts PX4 with its SIH simulator in the ":sitl" process, as a foreground
 * service so that it keeps running while another app is in front.
 *
 * Any app may start it with [ACTION_START] and stop it with [ACTION_STOP].
 * PX4 cannot be shut down and started again within a process, so stopping
 * the service kills that process; the next start is a fresh boot.
 */
class SitlService : Service() {
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        val modelId = intent.extra(EXTRA_MODEL) ?: SitlConfig.models.first().id
        val model = SitlConfig.model(modelId)
        val speedup = (intent.extra(EXTRA_SPEEDUP) ?: "1").toDoubleOrNull()
        if (model == null || speedup == null || !speedup.isFinite() || speedup <= 0 ||
            !SitlConfig.isValidPort(mavlinkPort)) {
            Log.e(TAG, "unsupported model \"$modelId\", speedup, or MAVLink port")
            stopSelf()
            return START_NOT_STICKY
        }
        started = true

        val workDir = SitlConfig.workDir(filesDir, model)
        if (intent?.getBooleanExtra(EXTRA_WIPE, false) == true) {
            workDir.deleteRecursively()
        }
        workDir.mkdirs()
        SitlConfig.unpack(assets, "px4/etc", SitlConfig.dataDir(filesDir))
        SitlConfig.unpack(assets, "px4/bin", SitlConfig.binDir(filesDir))
        SitlConfig.currentFile(filesDir).writeText("${model.id} $speedup $mavlinkPort")

        val environment = mutableListOf(
            // by number: given the model's name, rcS would look the number up with sed
            "PX4_SYS_AUTOSTART", model.autostart.toString(),
            // read by the simulator itself to pace simulated against real time
            "PX4_SIM_SPEED_FACTOR", speedup.toString(),
            "PX4_ANDROID_MAVLINK_PORT", mavlinkPort,
        )
        intent.extra(EXTRA_HOME)?.split(',')?.let { home ->
            listOf("PX4_HOME_LAT", "PX4_HOME_LON", "PX4_HOME_ALT", "PX4_HOME_YAW").zip(home).forEach { (name, value) ->
                environment += listOf(name, value.trim())
            }
        }

        val args = arrayOf(
            // PX4 looks for px4-alias.sh next to its program
            File(SitlConfig.binDir(filesDir), "px4").absolutePath,
            // no server socket and no px4-* clients, neither of which an app can have
            "-e",
            // no interactive shell
            "-d",
            "-w", workDir.absolutePath,
            "-s", startupScript(speedup).absolutePath,
            SitlConfig.dataDir(filesDir).absolutePath,
        )

        // PX4 takes over this thread, give it a desktop-sized stack
        Thread(null, {
            // first of all, so that nothing of this simulator runs next to another one
            if (!SimLock.acquire(LOCK_NAME)) {
                Log.e(TAG, "another simulator is running and does not stop")
                Process.killProcess(Process.myPid())
            }
            // they bind their ports at once and find PX4 when it comes up
            SimCamera().start()
            SimGimbal().start()

            // PX4 passes each simulation step through a chain of threads. They
            // inherit this priority, without which the app in front of this
            // service keeps them waiting at every link.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val ret = SitlNative.run(SitlConfig.logFile(filesDir).absolutePath,
                environment.toTypedArray(), args)
            Log.e(TAG, "PX4 returned $ret")
            Process.killProcess(Process.myPid())
        }, "px4-main", 16L * 1024 * 1024).start()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Process.killProcess(Process.myPid())
    }

    /**
     * Wraps PX4's rcS. Given a speed factor, rcS stretches four timeouts by it
     * so that they still mean real time, and computes them with awk. Not
     * every Android has awk, so rcS is not told and the same is done here.
     */
    private fun startupScript(speedup: Double): File {
        fun scaled(seconds: Double) = String.format(Locale.US, "%.3f", seconds * speedup)
        val script = File(SitlConfig.binDir(filesDir), "rcS-android")
        script.writeText(
            """
            #!/bin/sh
            unset PX4_SIM_SPEED_FACTOR
            . etc/init.d-posix/rcS
            param set COM_DL_LOSS_T ${scaled(10.0)}
            param set COM_RC_LOSS_T ${scaled(1.0)}
            param set COM_OF_LOSS_T ${scaled(1.0)}
            param set COM_DISARM_PRFLT ${scaled(10.0)}
            # the gimbal manager, off by default, with SimGimbal as its gimbal.
            # rcS starts it by itself once the parameters are saved.
            if ! param greater -s MNT_MODE_IN -1
            then
                param set MNT_MODE_IN 4
                param set MNT_MODE_OUT 2
                gimbal start
            fi
            """.trimIndent() + "\n")
        return script
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
            .setContentTitle("PX4 SIH is running")
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
        private const val LOCK_NAME = "px4"

        const val ACTION_START = "ch.vautherin.px4_sih.action.START"
        const val ACTION_STOP = "ch.vautherin.px4_sih.action.STOP"

        /** Simulation speed relative to real time, default 1. */
        const val EXTRA_SPEEDUP = "speedup"
        /** Loopback UDP destination port for the app under test. */
        const val EXTRA_MAVLINK_PORT = "mavlink_port"
        /** One of [SitlConfig.models], default the first. */
        const val EXTRA_MODEL = "model"
        /** Start location as "lat,lon,alt,heading". */
        const val EXTRA_HOME = "home"
        /** Boolean, erase the model's stored parameters, missions and logs before starting. */
        const val EXTRA_WIPE = "wipe"

        /** Loopback UDP port PX4 sends its offboard MAVLink link to, for the app under test. */
        const val DEFAULT_MAVLINK_PORT = 14540
        /** Loopback UDP port PX4 sends its ground station link to, used by this app. */
        const val MONITOR_PORT = 14550
    }
}
