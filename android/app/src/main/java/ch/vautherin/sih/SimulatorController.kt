/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.sih

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Keeps simulator transitions and settings across activity recreation. */
class SimulatorController(application: Application) : AndroidViewModel(application) {
    enum class Status { CHECKING, STOPPED, STARTING, RUNNING, STOPPING }

    val monitor = VehicleMonitor(application.filesDir)
    var status by mutableStateOf(Status.CHECKING)
        private set
    var settings by mutableStateOf(SitlConfig.Settings())
        private set
    var error by mutableStateOf("")
        private set

    val running: Boolean get() = status == Status.RUNNING || status == Status.STARTING

    private var observing = false
    private var settingsLoaded = false
    private var pollJob: Job? = null
    private var transitionJob: Job? = null

    fun observe() {
        observing = true
        if (transitionJob?.isActive != true) monitor.start()
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            if (!settingsLoaded) {
                val saved = withContext(Dispatchers.IO) { SitlConfig.readSettings(getApplication<Application>().filesDir) }
                if (!settingsLoaded && saved != null) settings = saved
                settingsLoaded = true
            }
            while (isActive) {
                val transition = transitionJob
                val running = withContext(Dispatchers.IO) { SimLock.isRunning(Autopilot.id) }
                if (transition === transitionJob && transition?.isActive != true) {
                    status = if (running) Status.RUNNING else Status.STOPPED
                    if (running) {
                        val current = withContext(Dispatchers.IO) {
                            SitlConfig.readSettings(getApplication<Application>().filesDir)
                        }
                        if (transition === transitionJob && transition?.isActive != true && current != null) {
                            settings = current
                        }
                    }
                }
                delay(500)
            }
        }
    }

    fun pause() {
        observing = false
        pollJob?.cancel()
        monitor.stop()
    }

    fun updateSettings(value: SitlConfig.Settings) {
        if (!SitlConfig.isValidSpeed(value.speedup) || !SitlConfig.isValidPort(value.mavlinkPort)) return
        if (value == settings) return
        settingsLoaded = true
        settings = value
        if (running) start()
    }

    fun toggle() {
        if (running) stop() else if (status == Status.STOPPED) start()
    }

    private fun start() = transition(start = true)

    private fun stop() = transition(start = false)

    private fun transition(start: Boolean) {
        transitionJob?.cancel()
        monitor.stop()
        status = if (start) Status.STARTING else Status.STOPPING
        error = ""
        transitionJob = viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                app.stopService(Intent(app, Autopilot.serviceClass))
                withTimeout(10_000) {
                    // The lock closes only when the old simulator process has exited.
                    while (withContext(Dispatchers.IO) { SimLock.isRunning(Autopilot.id) }) delay(50)
                    // A service may still be starting and not hold the lock yet.
                    delay(300)
                    if (start) {
                        app.startForegroundService(Autopilot.startIntent(app, settings))
                        while (!withContext(Dispatchers.IO) { SimLock.isRunning(Autopilot.id) }) delay(100)
                    }
                }
                status = if (start) Status.RUNNING else Status.STOPPED
            } catch (e: TimeoutCancellationException) {
                error = "Simulator ${if (start) "start" else "stop"} timed out"
                status = Status.CHECKING
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Simulator failed: ${e.message}"
                status = Status.CHECKING
            } finally {
                if (observing && currentCoroutineContext()[Job] === transitionJob) monitor.start()
            }
        }
    }

    override fun onCleared() {
        observing = false
        pollJob?.cancel()
        transitionJob?.cancel()
        monitor.stop()
    }
}
