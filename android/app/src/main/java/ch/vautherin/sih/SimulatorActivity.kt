/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.sih

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme

/** Controls the simulator service and shows the vehicle on a map. */
open class SimulatorActivity : ComponentActivity() {
    private val simulator by viewModels<SimulatorController>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                SitlScreen(
                    state = simulator.monitor.state,
                    simulatorStatus = simulator.status,
                    settings = simulator.settings,
                    onSettingsChange = simulator::updateSettings,
                    onToggle = simulator::toggle,
                    onTakeoff = simulator.monitor::takeoff,
                    onLand = simulator.monitor::land,
                    onRtl = simulator.monitor::returnToLaunch,
                )
            }
        }
        // without it the service still runs, only its notification is hidden
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onStart() {
        super.onStart()
        simulator.observe()
    }

    override fun onStop() {
        super.onStop()
        simulator.pause()
    }
}
