/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.px4_sih

import android.os.SystemClock
import android.util.Log
import io.mavsdk.kotlin.ComponentType
import io.mavsdk.kotlin.Mavsdk
import io.mavsdk.kotlin.plugins.mavlink_direct_server.MavlinkDirectServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A gimbal on the simulated vehicle, which PX4 itself does not simulate. It
 * is a MAVLink component of its own, a gimbal device of the gimbal protocol
 * v2, and it moves nothing: pitch and yaw slew towards what they are told.
 *
 * It is not what the app under test talks to. That is PX4's gimbal manager,
 * which keeps track of who is in control and passes the setpoints on to this
 * device, on the UDP port PX4 sends its gimbal link to. PX4 relays what the
 * device says about itself to the other links.
 *
 * MAVSDK has no plugin for a gimbal device, so the messages are written and
 * read one by one, with the plugin that gives a component access to them.
 *
 * Lives as long as the process, like PX4.
 */
class SimGimbal {
    // one thread, so that the handlers share the state below without locks
    private val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
    private lateinit var mavsdk: Mavsdk
    private lateinit var direct: MavlinkDirectServer

    // where to go, each null when only the rate counts
    private var pitchSetpointDeg: Double? = 0.0
    private var yawSetpointDeg: Double? = 0.0
    private var pitchRateSetpointDegS: Double? = null
    private var yawRateSetpointDegS: Double? = null
    /** Whether the yaw setpoint is relative to North, and not to the vehicle. */
    private var yawInEarthFrame = false
    private var yawLock = false

    // where it is, with yaw relative to the vehicle
    private var pitchDeg = 0.0
    private var yawDeg = 0.0
    private var pitchRateDegS = 0.0
    private var yawRateDegS = 0.0

    private var vehicleYawDeg = 0.0

    fun start() {
        // the vehicle's system id, and the component id of a gimbal
        mavsdk = Mavsdk(ComponentType.GIMBAL)
        mavsdk.addAnyConnection("udpin://127.0.0.1:$PORT").onFailure {
            Log.e(TAG, "no gimbal, cannot listen on UDP port $PORT: $it")
            return
        }
        direct = mavsdk.serverComponent().mavlinkDirectServer
        // from whoever sends them: PX4 relays the requests of other systems, e.g. of a ground station
        scope.launch { direct.subscribeMessage("GIMBAL_DEVICE_SET_ATTITUDE").collect { setAttitude(it) } }
        scope.launch { direct.subscribeMessage("AUTOPILOT_STATE_FOR_GIMBAL_DEVICE").collect { vehicleState(it) } }
        scope.launch { direct.subscribeMessage("COMMAND_LONG").collect { command(it) } }
        scope.launch { run() }
    }

    private suspend fun run() {
        var tick = 0
        while (true) {
            delay(STEP_MS)
            val dt = STEP_MS / 1000.0
            // a setpoint relative to North moves under the gimbal as the vehicle turns
            val yawSetpoint = yawSetpointDeg?.let { if (yawInEarthFrame) wrap180(it - vehicleYawDeg) else it }
            pitchRateDegS = step(dt, pitchSetpointDeg, pitchRateSetpointDegS, PITCH_MIN_DEG, PITCH_MAX_DEG, pitchDeg)
            pitchDeg += pitchRateDegS * dt
            yawRateDegS = step(dt, yawSetpoint, yawRateSetpointDegS, YAW_MIN_DEG, YAW_MAX_DEG, yawDeg)
            yawDeg += yawRateDegS * dt
            // 10 Hz, the least the protocol asks for
            if (++tick % 5 == 0) {
                sendAttitudeStatus()
            }
        }
    }

    /** The rate at which an axis moves in this step. */
    private fun step(dt: Double, setpointDeg: Double?, rateSetpointDegS: Double?,
                     minDeg: Double, maxDeg: Double, angleDeg: Double): Double {
        val next = when {
            // the rate setpoint, if any, is how fast to get to the angle
            setpointDeg != null -> {
                val maxStep = abs(rateSetpointDegS ?: SLEW_RATE_DEG_S) * dt
                angleDeg + (setpointDeg - angleDeg).coerceIn(-maxStep, maxStep)
            }
            rateSetpointDegS != null -> angleDeg + rateSetpointDegS * dt
            else -> angleDeg
        }
        return (next.coerceIn(minDeg, maxDeg) - angleDeg) / dt
    }

    private fun setAttitude(message: MavlinkDirectServer.MavlinkMessage) {
        val fields = JSONObject(message.fieldsJson)
        if (!forUs(fields)) return
        val flags = fields.optInt("flags")
        yawLock = flags and FLAG_YAW_LOCK != 0
        // a manager from before the frame flags existed sets neither, and means the lock
        yawInEarthFrame = flags and FLAG_YAW_IN_EARTH_FRAME != 0 ||
            (flags and FLAG_YAW_IN_VEHICLE_FRAME == 0 && yawLock)

        val q = fields.optJSONArray("q")
        if (flags and (FLAG_NEUTRAL or FLAG_RETRACT) != 0) {
            pitchSetpointDeg = 0.0
            yawSetpointDeg = 0.0
            yawInEarthFrame = false
        } else if (q != null && q.length() == 4 && (0 until 4).none { q.isNull(it) }) {
            val (pitch, yaw) = pitchYawDeg(q.getDouble(0), q.getDouble(1), q.getDouble(2), q.getDouble(3))
            pitchSetpointDeg = pitch
            yawSetpointDeg = yaw
        } else {
            // not a number, which JSON has no way to write: only the rates count
            pitchSetpointDeg = null
            yawSetpointDeg = null
        }
        pitchRateSetpointDegS = fields.optDouble("angular_velocity_y").takeUnless { it.isNaN() }?.let(Math::toDegrees)
        yawRateSetpointDegS = fields.optDouble("angular_velocity_z").takeUnless { it.isNaN() }?.let(Math::toDegrees)
    }

    private fun vehicleState(message: MavlinkDirectServer.MavlinkMessage) {
        val q = JSONObject(message.fieldsJson).optJSONArray("q_estimated_attitude") ?: return
        if (q.length() != 4 || (0 until 4).any { q.isNull(it) }) return
        vehicleYawDeg = pitchYawDeg(q.getDouble(0), q.getDouble(1), q.getDouble(2), q.getDouble(3)).second
    }

    private fun command(message: MavlinkDirectServer.MavlinkMessage) {
        val fields = JSONObject(message.fieldsJson)
        if (!forUs(fields)) return
        // everything else, MAVSDK answers or leaves to others
        if (fields.optInt("command") != CMD_REQUEST_MESSAGE ||
            fields.optDouble("param1").toInt() != MSG_ID_GIMBAL_DEVICE_INFORMATION) return
        send("COMMAND_ACK", JSONObject()
            .put("command", CMD_REQUEST_MESSAGE)
            .put("result", 0)
            .put("target_system", message.systemId)
            .put("target_component", message.componentId))
        send("GIMBAL_DEVICE_INFORMATION", JSONObject()
            .put("time_boot_ms", SystemClock.elapsedRealtime())
            .put("vendor_name", "PX4")
            .put("model_name", "SIH gimbal")
            .put("custom_name", "")
            .put("cap_flags", CAP_FLAGS)
            .put("roll_min", 0.0)
            .put("roll_max", 0.0)
            .put("pitch_min", Math.toRadians(PITCH_MIN_DEG))
            .put("pitch_max", Math.toRadians(PITCH_MAX_DEG))
            .put("yaw_min", Math.toRadians(YAW_MIN_DEG))
            .put("yaw_max", Math.toRadians(YAW_MAX_DEG))
            // 0 for a gimbal that is a component of its own
            .put("gimbal_device_id", 0))
    }

    private fun sendAttitudeStatus() {
        // roll and pitch are always held against the horizon
        var flags = FLAG_ROLL_LOCK or FLAG_PITCH_LOCK or FLAG_YAW_IN_VEHICLE_FRAME
        if (yawLock) flags = flags or FLAG_YAW_LOCK
        send("GIMBAL_DEVICE_ATTITUDE_STATUS", JSONObject()
            .put("time_boot_ms", SystemClock.elapsedRealtime())
            .put("flags", flags)
            .put("q", quaternion(pitchDeg, yawDeg))
            .put("angular_velocity_x", 0.0)
            .put("angular_velocity_y", Math.toRadians(pitchRateDegS))
            .put("angular_velocity_z", Math.toRadians(yawRateDegS))
            .put("failure_flags", 0)
            .put("gimbal_device_id", 0))
    }

    /** To everyone. Goes nowhere until PX4 is up, which is as well. */
    private fun send(name: String, fields: JSONObject) {
        val result = direct.sendMessage(MavlinkDirectServer.MavlinkMessage(name, 0, 0, 0, 0, fields.toString()))
        if (result != MavlinkDirectServer.Result.SUCCESS) {
            Log.e(TAG, "cannot send $name: $result")
        }
    }

    private fun forUs(fields: JSONObject): Boolean {
        val system = fields.optInt("target_system")
        val component = fields.optInt("target_component")
        return (system == 0 || system == SYSTEM_ID) && (component == 0 || component == COMPONENT_ID)
    }

    /** Pitch and yaw of a rotation, in the yaw-pitch-roll order of MAVLink. */
    private fun pitchYawDeg(w: Double, x: Double, y: Double, z: Double): Pair<Double, Double> {
        val pitch = asin((2 * (w * y - z * x)).coerceIn(-1.0, 1.0))
        val yaw = atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z))
        return Pair(Math.toDegrees(pitch), Math.toDegrees(yaw))
    }

    /** The rotation with that pitch and yaw, and no roll. */
    private fun quaternion(pitchDeg: Double, yawDeg: Double): JSONArray {
        val p = Math.toRadians(pitchDeg) / 2
        val y = Math.toRadians(yawDeg) / 2
        return JSONArray()
            .put(cos(p) * cos(y))
            .put(-sin(p) * sin(y))
            .put(sin(p) * cos(y))
            .put(cos(p) * sin(y))
    }

    private fun wrap180(deg: Double): Double {
        var wrapped = deg % 360
        if (wrapped > 180) wrapped -= 360
        if (wrapped < -180) wrapped += 360
        return wrapped
    }

    companion object {
        private const val TAG = "SimGimbal"
        private const val STEP_MS = 20L
        private const val SLEW_RATE_DEG_S = 60.0
        private const val PITCH_MIN_DEG = -90.0
        private const val PITCH_MAX_DEG = 30.0
        private const val YAW_MIN_DEG = -160.0
        private const val YAW_MAX_DEG = 160.0

        // PX4's, and MAV_COMP_ID_GIMBAL, which is what PX4 looks for (MNT_MAV_COMPID)
        private const val SYSTEM_ID = 1
        private const val COMPONENT_ID = 154

        private const val CMD_REQUEST_MESSAGE = 512
        private const val MSG_ID_GIMBAL_DEVICE_INFORMATION = 283

        // GIMBAL_DEVICE_FLAGS
        private const val FLAG_RETRACT = 1
        private const val FLAG_NEUTRAL = 2
        private const val FLAG_ROLL_LOCK = 4
        private const val FLAG_PITCH_LOCK = 8
        private const val FLAG_YAW_LOCK = 16
        private const val FLAG_YAW_IN_VEHICLE_FRAME = 32
        private const val FLAG_YAW_IN_EARTH_FRAME = 64

        // GIMBAL_DEVICE_CAP_FLAGS: neutral, pitch axis and lock, yaw axis, follow and lock
        private const val CAP_FLAGS = 2 or 32 or 128 or 256 or 512 or 1024

        /** Loopback UDP port PX4 sends its gimbal link to. */
        const val PORT = 13280
    }
}
