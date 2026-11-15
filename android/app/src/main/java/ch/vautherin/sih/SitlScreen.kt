/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.sih

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sign
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val FOLLOW_ZOOM = 16.0
private const val MIN_ZOOM = 3.0
private const val MAX_ZOOM = 19.0
private const val ZOOM_EASE_PER_SECOND = 4.0

// where SITL starts unless told otherwise, so the map opens there
private val DEFAULT_HOME = Position(longitude = 149.165237, latitude = -35.363262)

@Composable
fun SitlScreen(
    state: VehicleState,
    simulatorStatus: SimulatorController.Status,
    settings: SitlConfig.Settings,
    onSettingsChange: (SitlConfig.Settings) -> Unit,
    onToggle: () -> Unit,
    onTakeoff: () -> Unit,
    onLand: () -> Unit,
    onRtl: () -> Unit,
) {
    val cameraState = rememberCameraState(CameraPosition(target = DEFAULT_HOME, zoom = FOLLOW_ZOOM))

    // The map stays centred on the vehicle, which is why its icon can simply
    // be drawn over the middle of the map. Positions arrive a few times a
    // second, so the camera glides from each one to the next instead of
    // jumping.
    //
    // This screen owns the whole camera, zoom included: the map's own
    // gestures are off and pinches are handled here. Left to the map, a pinch
    // and the per-frame following would each keep overwriting the other.
    val latitude = state.latitude
    val longitude = state.longitude
    val target by rememberUpdatedState(
        if (latitude != null && longitude != null) Pose(latitude, longitude, state.headingDeg) else null)
    var shown by remember { mutableStateOf<Pose?>(null) }
    var zoom by remember { mutableDoubleStateOf(FOLLOW_ZOOM) }
    LaunchedEffect(Unit) {
        var from: Pose? = null
        var to: Pose? = null
        var legStartNanos = 0L
        var legNanos = 1L
        var lastFrameNanos = 0L
        var shownZoom = zoom
        while (true) {
            withFrameNanos { now ->
                val wanted = target
                var pose: Pose? = null
                if (wanted == null) {
                    from = null
                    to = null
                } else {
                    // Finish the shortest glide even when accelerated fixes arrive every frame.
                    if (wanted != to && now - legStartNanos >= MIN_LEG_NANOS) {
                        // a new fix: travel to it in the time the last one took to arrive
                        val current = shown
                        legNanos = (now - legStartNanos).coerceIn(MIN_LEG_NANOS, MAX_LEG_NANOS)
                        legStartNanos = now
                        from = if (current == null || current.isFarFrom(wanted)) wanted else current
                        to = wanted
                    }
                    val fraction = ((now - legStartNanos).toFloat() / legNanos).coerceIn(0f, 1f)
                    pose = (from ?: wanted).towards(wanted, fraction)
                }

                // a pinch moves the zoom a little every frame and is followed
                // as is; a double tap is a whole step, which is eased
                val zoomStep = ZOOM_EASE_PER_SECOND * (now - lastFrameNanos) / 1e9
                lastFrameNanos = now
                val newZoom =
                    if (abs(zoom - shownZoom) <= zoomStep) zoom
                    else shownZoom + zoomStep * sign(zoom - shownZoom)

                if (pose != shown || newZoom != shownZoom) {
                    shown = pose
                    shownZoom = newZoom
                    cameraState.position = CameraPosition(
                        target = pose?.let { Position(longitude = it.longitude, latitude = it.latitude) }
                            ?: cameraState.position.target,
                        zoom = newZoom,
                    )
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(MAP_STYLE),
            cameraState = cameraState,
            options = MapOptions(
                gestureOptions = GestureOptions.AllDisabled,
                ornamentOptions = OrnamentOptions(
                    padding = WindowInsets.safeDrawing.asPaddingValues(),
                    isCompassEnabled = false,
                    isScaleBarEnabled = false,
                ),
            ),
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        zoom = (zoom + log2(zoomChange)).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { zoom = (zoom + 1.0).coerceIn(MIN_ZOOM, MAX_ZOOM) })
                }
        )

        val pose = shown
        if (simulatorStatus == SimulatorController.Status.RUNNING && state.connected && pose != null) {
            VehicleIcon(
                isPlane = SitlConfig.isPlane(state.settings),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .rotate(pose.headingDeg),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
        ) {
            Controls(
                state, simulatorStatus, settings, onSettingsChange, onToggle, onTakeoff, onLand, onRtl,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            StatusPill(state, simulatorStatus, Modifier.align(Alignment.BottomCenter))
        }
    }
}

private const val MIN_LEG_NANOS = 50_000_000L
private const val MAX_LEG_NANOS = 1_000_000_000L

/** Where the vehicle is drawn. */
private data class Pose(val latitude: Double, val longitude: Double, val headingDeg: Float) {
    /** Too far to glide to, as after a restart at another location. */
    fun isFarFrom(other: Pose) =
        abs(latitude - other.latitude) > 0.01 || abs(longitude - other.longitude) > 0.01

    fun towards(other: Pose, fraction: Float): Pose {
        // the short way round, so 359 to 1 degrees turns through north
        val turn = ((other.headingDeg - headingDeg + 540f) % 360f) - 180f
        return Pose(
            latitude + (other.latitude - latitude) * fraction,
            longitude + (other.longitude - longitude) * fraction,
            (headingDeg + turn * fraction + 360f) % 360f,
        )
    }
}

@Composable
private fun Controls(
    state: VehicleState,
    simulatorStatus: SimulatorController.Status,
    settings: SitlConfig.Settings,
    onSettingsChange: (SitlConfig.Settings) -> Unit,
    onToggle: () -> Unit,
    onTakeoff: () -> Unit,
    onLand: () -> Unit,
    onRtl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preset = SitlConfig.selectedPreset(settings)
    var presetsOpen by remember { mutableStateOf(false) }
    var speedup by rememberSaveable(settings.speedup) { mutableStateOf(settings.speedup) }
    var mavlinkPort by rememberSaveable(settings.mavlinkPort) { mutableStateOf(settings.mavlinkPort) }
    val validSpeed = SitlConfig.isValidSpeed(speedup)
    val validPort = SitlConfig.isValidPort(mavlinkPort)
    val running = simulatorStatus == SimulatorController.Status.RUNNING ||
        simulatorStatus == SimulatorController.Status.STARTING
    val canEdit = simulatorStatus != SimulatorController.Status.CHECKING &&
        simulatorStatus != SimulatorController.Status.STOPPING
    val canCommand = simulatorStatus == SimulatorController.Status.RUNNING &&
        state.connected && !state.commandBusy
    val focusManager = LocalFocusManager.current

    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp, modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { focusManager.clearFocus(); presetsOpen = true },
                        enabled = canEdit,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(preset?.label ?: settings.model, maxLines = 1)
                    }
                    DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                        SitlConfig.presets.forEach {
                            DropdownMenuItem(
                                text = { Text(it.label) },
                                onClick = {
                                    onSettingsChange(SitlConfig.selectPreset(settings, it))
                                    presetsOpen = false
                                },
                            )
                        }
                    }
                }
                NumberField(
                    "Speed", speedup, { speedup = it },
                    onCommit = {
                        if (SitlConfig.isValidSpeed(speedup)) onSettingsChange(settings.copy(speedup = speedup))
                    },
                    valid = validSpeed, enabled = canEdit, decimal = true, modifier = Modifier.width(90.dp),
                )
                NumberField(
                    "UDP port", mavlinkPort, { mavlinkPort = it },
                    onCommit = {
                        if (SitlConfig.isValidPort(mavlinkPort)) onSettingsChange(settings.copy(mavlinkPort = mavlinkPort))
                    },
                    valid = validPort, enabled = canEdit, modifier = Modifier.width(110.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { focusManager.clearFocus(); onToggle() },
                    enabled = canEdit && (running || (validSpeed && validPort)),
                    modifier = Modifier.weight(1f),
                ) { Text(if (running) "Stop" else "Start") }
                OutlinedButton(
                    onClick = onTakeoff,
                    enabled = canCommand && state.readyForTakeoff,
                    modifier = Modifier.weight(1.3f),
                ) { Text("Take off", maxLines = 1) }
                OutlinedButton(
                    onClick = onLand,
                    enabled = canCommand && state.armed && SitlConfig.canLand(state.settings),
                    modifier = Modifier.weight(1f),
                ) { Text("Land") }
                OutlinedButton(
                    onClick = onRtl,
                    enabled = canCommand && state.armed,
                    modifier = Modifier.weight(1f),
                ) { Text("RTL") }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    onCommit: () -> Unit,
    valid: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    decimal: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || (decimal && it == '.') }) },
        label = { Text(label, maxLines = 1) },
        isError = !valid,
        enabled = enabled,
        supportingText = if (valid) null else ({ Text(if (decimal) "Must be > 0" else "1-65535") }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onCommit(); focusManager.clearFocus() }),
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused) onCommit()
            focused = it.isFocused
        },
    )
}

@Composable
private fun StatusPill(
    state: VehicleState,
    simulatorStatus: SimulatorController.Status,
    modifier: Modifier = Modifier,
) {
    Surface(shape = RoundedCornerShape(50), shadowElevation = 4.dp, modifier = modifier) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.connected || simulatorStatus != SimulatorController.Status.RUNNING) {
                val text = when (simulatorStatus) {
                    SimulatorController.Status.CHECKING -> "Checking simulator"
                    SimulatorController.Status.STOPPED -> "Simulator stopped"
                    SimulatorController.Status.STARTING -> "Starting simulator"
                    SimulatorController.Status.STOPPING -> "Stopping simulator"
                    SimulatorController.Status.RUNNING -> "Connecting to simulator"
                }
                Text(text, style = MaterialTheme.typography.titleMedium)
            } else {
                Text(
                    String.format(Locale.US, "%.1f m", state.relativeAltM),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    String.format(Locale.US, "%s · %.1fx", if (state.armed) "armed" else "disarmed", state.simSpeed),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Drawn nose up; the caller rotates it to the vehicle's heading. */
@Composable
private fun VehicleIcon(isPlane: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // a light halo first, so the shape reads on any map background
        if (isPlane) {
            drawPlane(Color.White, size.minDimension * 0.10f)
            drawPlane(Color(0xFF1A237E), 0f)
        } else {
            drawQuad(Color.White, size.minDimension * 0.07f)
            drawQuad(Color(0xFF1A237E), 0f)
        }
    }
}

private fun DrawScope.drawPlane(color: Color, outline: Float) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.50f, h * 0.04f)  // nose
        lineTo(w * 0.57f, h * 0.36f)
        lineTo(w * 0.98f, h * 0.56f)  // right wing tip
        lineTo(w * 0.98f, h * 0.66f)
        lineTo(w * 0.57f, h * 0.58f)
        lineTo(w * 0.55f, h * 0.80f)
        lineTo(w * 0.70f, h * 0.90f)  // right tailplane
        lineTo(w * 0.70f, h * 0.96f)
        lineTo(w * 0.50f, h * 0.92f)
        lineTo(w * 0.30f, h * 0.96f)
        lineTo(w * 0.30f, h * 0.90f)
        lineTo(w * 0.45f, h * 0.80f)
        lineTo(w * 0.43f, h * 0.58f)
        lineTo(w * 0.02f, h * 0.66f)
        lineTo(w * 0.02f, h * 0.56f)  // left wing tip
        lineTo(w * 0.43f, h * 0.36f)
        close()
    }
    drawPath(path, color)
    if (outline > 0f) {
        drawPath(path, color, style = Stroke(width = outline))
    }
}

private fun DrawScope.drawQuad(color: Color, outline: Float) {
    val c = Offset(size.width / 2, size.height / 2)
    val arm = size.minDimension * 0.30f
    val rotor = size.minDimension * 0.17f
    val motors = listOf(
        Offset(c.x - arm, c.y - arm), Offset(c.x + arm, c.y - arm),
        Offset(c.x - arm, c.y + arm), Offset(c.x + arm, c.y + arm),
    )
    motors.forEach { motor ->
        drawLine(color, c, motor, strokeWidth = size.minDimension * 0.09f + outline, cap = StrokeCap.Round)
    }
    motors.forEach { motor -> drawCircle(color, rotor + outline / 2, motor) }
    // a nose marker, as a quad is otherwise symmetric
    val nose = Path().apply {
        moveTo(c.x, c.y - arm * 1.25f)
        lineTo(c.x + arm * 0.35f, c.y - arm * 0.45f)
        lineTo(c.x - arm * 0.35f, c.y - arm * 0.45f)
        close()
    }
    drawPath(nose, color)
    if (outline > 0f) {
        drawPath(nose, color, style = Stroke(width = outline))
    }
}
