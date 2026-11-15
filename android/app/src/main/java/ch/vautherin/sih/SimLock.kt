/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.sih

import android.os.Process
import android.os.SystemClock
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Keeps this simulator the only one on the device. Both app flavors
 * send to the same UDP port, so the two must never run together.
 *
 * Whichever simulator runs holds [PORT], a loopback TCP port, which the system
 * gives to one process at a time. A simulator that starts tells the holder to
 * stop and takes the port over, so the last one started wins.
 *
 * Any app can do the same to stop the simulator, and knows it is gone when
 * the connection closes:
 *
 *     Socket("127.0.0.1", 14539).use {
 *         it.getOutputStream().write("stop\n".toByteArray())
 *         while (it.getInputStream().read() != -1) { }
 *     }
 *
 * A line "status" is answered with the simulator's name instead.
 */
object SimLock {
    /** The same in every simulator app that takes part. */
    const val PORT = 14539

    private const val ACQUIRE_TIMEOUT_MS = 5000L
    private const val HOLDER_TIMEOUT_MS = 2000

    fun isRunning(name: String): Boolean = try {
        Socket().use { holder ->
            holder.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), HOLDER_TIMEOUT_MS)
            holder.soTimeout = HOLDER_TIMEOUT_MS
            holder.getOutputStream().write("status\n".toByteArray())
            holder.getInputStream().bufferedReader().readLine() == name
        }
    } catch (e: IOException) {
        false
    }

    /** Stops any other simulator. False if it would not go, and this one must not start. */
    fun acquire(name: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + ACQUIRE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                val server = ServerSocket(PORT, 4, InetAddress.getLoopbackAddress())
                thread(name = "sim-lock", isDaemon = true) { serve(server, name) }
                return true
            } catch (e: IOException) {
                stopHolder()
            }
        }
        return false
    }

    private fun stopHolder() {
        try {
            Socket().use { holder ->
                holder.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), HOLDER_TIMEOUT_MS)
                holder.soTimeout = HOLDER_TIMEOUT_MS
                holder.getOutputStream().write("stop\n".toByteArray())
                // closed by the system when the holder's process is gone
                while (holder.getInputStream().read() != -1) { }
            }
        } catch (e: IOException) {
            // gone already, or it went with the connection open
        }
        SystemClock.sleep(50)
    }

    private fun serve(server: ServerSocket, name: String) {
        while (true) {
            try {
                server.accept().use { client ->
                    // a client that says nothing must not keep the next one waiting
                    client.soTimeout = HOLDER_TIMEOUT_MS
                    when (client.getInputStream().bufferedReader().readLine()) {
                        // not closing the connection: the system does, once nothing of this process is left
                        "stop" -> Process.killProcess(Process.myPid())
                        "status" -> client.getOutputStream().write("$name\n".toByteArray())
                    }
                }
            } catch (e: IOException) {
                // that client's problem
            }
        }
    }
}
