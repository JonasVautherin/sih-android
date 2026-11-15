/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.ap_sitl

import ch.vautherin.sih.SitlConfig

/** Vehicle-specific takeoff and landing policies, and mode labels. */
class Flight private constructor(
    val takeoffMode: Int,
    val takeoffCommand: Boolean,
    val landMode: Int?,
    val modes: Map<Int, String>,
) {
    companion object {
        private val COPTER_MODES = mapOf(
            0 to "STABILIZE", 2 to "ALT_HOLD", 3 to "AUTO", 4 to "GUIDED",
            5 to "LOITER", 6 to "RTL", 9 to "LAND",
        )
        private val PLANE_MODES = mapOf(
            0 to "MANUAL", 1 to "CIRCLE", 2 to "STABILIZE", 5 to "FBWA", 6 to "FBWB",
            7 to "CRUISE", 10 to "AUTO", 11 to "RTL", 12 to "LOITER", 13 to "TAKEOFF",
            15 to "GUIDED", 17 to "QSTABILIZE", 18 to "QHOVER", 19 to "QLOITER",
            20 to "QLAND", 21 to "QRTL",
        )
        // GUIDED, then a take-off command; LAND
        private val COPTER = Flight(4, true, 9, COPTER_MODES)
        // the same in Plane's numbering; QLAND
        private val VTOL = Flight(15, true, 20, PLANE_MODES)
        // TAKEOFF mode launches on arming; landing requires a mission.
        private val FIXED_WING = Flight(13, false, null, PLANE_MODES)

        fun of(vehicle: String, model: String): Flight = when {
            vehicle != SitlConfig.Vehicle.PLANE.id -> COPTER
            SitlConfig.isVtol(model) -> VTOL
            else -> FIXED_WING
        }
    }
}
