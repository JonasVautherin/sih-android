/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.sih

import android.util.Log
import io.mavsdk.kotlin.ComponentType
import io.mavsdk.kotlin.Mavsdk
import io.mavsdk.kotlin.plugins.camera_server.CameraServer
import io.mavsdk.kotlin.plugins.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A camera component made with MAVSDK's camera server. It takes no pictures:
 * it answers the camera protocol and keeps count.
 *
 * It listens on the vehicle's camera link, which the autopilot routes to the
 * app under test as a second component of the vehicle.
 *
 * Lives as long as the process, like the simulator.
 */
class SimCamera {
    // one thread, so that the handlers share the state below without locks
    private val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
    private lateinit var mavsdk: Mavsdk
    private var telemetry: Telemetry? = null

    private var imageCount = 0
    private var capturing = false
    private var recordingSinceMs = 0L

    fun start() {
        // the vehicle's system id, and the component id of a camera
        mavsdk = Mavsdk(ComponentType.CAMERA)
        mavsdk.addAnyConnection("udpin://127.0.0.1:$PORT").onFailure {
            Log.e(TAG, "no camera, cannot listen on UDP port $PORT: $it")
            return
        }
        // ahead of the first picture, which needs to know where the vehicle is
        scope.launch {
            telemetry = mavsdk.subscribeOnNewSystem().first { it.hasAutopilot() }.telemetry
        }
        val server = mavsdk.serverComponent().cameraServer
        // every request has to be answered, so all handlers come before the camera shows up
        answerRequests(server)
        server.setInProgress(false)
        val result = server.setInformation(CameraServer.Information(
            vendorName = Autopilot.cameraVendor,
            modelName = Autopilot.cameraModel,
            firmwareVersion = "1.0.0",
            focalLengthMm = 3.0f,
            horizontalSensorSizeMm = 3.68f,
            verticalSensorSizeMm = 2.76f,
            horizontalResolutionPx = 3280,
            verticalResolutionPx = 2464,
            lensId = 0,
            definitionFileVersion = 0,
            definitionFileUri = "",
            imageInVideoModeSupported = true,
            videoInImageModeSupported = true,
        ))
        if (result != CameraServer.Result.SUCCESS) {
            Log.e(TAG, "no camera: $result")
        }
    }

    private fun answerRequests(server: CameraServer) {
        server.subscribeTakePhoto().handle { index ->
            capturing = true
            server.setInProgress(true)
            delay(CAPTURE_MS)
            imageCount++
            capturing = false
            server.setInProgress(false)
            server.respondTakePhoto(CameraServer.CameraFeedback.OK, captureInfo(index))
        }
        server.subscribeStartVideo().handle {
            recordingSinceMs = java.lang.System.currentTimeMillis()
            server.respondStartVideo(CameraServer.CameraFeedback.OK)
        }
        server.subscribeStopVideo().handle {
            recordingSinceMs = 0L
            server.respondStopVideo(CameraServer.CameraFeedback.OK)
        }
        server.subscribeSetMode().handle {
            server.respondSetMode(CameraServer.CameraFeedback.OK)
        }
        server.subscribeCaptureStatus().handle {
            val recording = recordingSinceMs != 0L
            server.respondCaptureStatus(CameraServer.CameraFeedback.OK, CameraServer.CaptureStatus(
                imageIntervalS = 0f,
                recordingTimeS = if (recording) (java.lang.System.currentTimeMillis() - recordingSinceMs) / 1000f else 0f,
                availableCapacityMib = STORAGE_MIB - usedMib(),
                imageStatus = if (capturing) CameraServer.ImageStatus.CAPTURE_IN_PROGRESS else CameraServer.ImageStatus.IDLE,
                videoStatus = if (recording) CameraServer.VideoStatus.CAPTURE_IN_PROGRESS else CameraServer.VideoStatus.IDLE,
                imageCount = imageCount,
            ))
        }
        server.subscribeStorageInformation().handle {
            server.respondStorageInformation(CameraServer.CameraFeedback.OK, CameraServer.StorageInformation(
                usedStorageMib = usedMib(),
                availableStorageMib = STORAGE_MIB - usedMib(),
                totalStorageMib = STORAGE_MIB,
                storageStatus = CameraServer.StorageStatus.FORMATTED,
                storageId = 1,
                storageType = CameraServer.StorageType.MICROSD,
                readSpeedMibS = 0f,
                writeSpeedMibS = 0f,
            ))
        }
        server.subscribeFormatStorage().handle {
            imageCount = 0
            server.respondFormatStorage(CameraServer.CameraFeedback.OK)
        }
        server.subscribeResetSettings().handle {
            server.respondResetSettings(CameraServer.CameraFeedback.OK)
        }
        // there is no video stream to start
        server.subscribeStartVideoStreaming().handle {
            server.respondStartVideoStreaming(CameraServer.CameraFeedback.FAILED)
        }
        server.subscribeStopVideoStreaming().handle {
            server.respondStopVideoStreaming(CameraServer.CameraFeedback.FAILED)
        }
    }

    private fun <T> Flow<T>.handle(action: suspend (T) -> Unit) {
        scope.launch { collect { action(it) } }
    }

    /**
     * Where the picture was taken, from what the vehicle sends on the camera link.
     * The camera is fixed to the vehicle, so it has the vehicle's attitude.
     */
    private fun captureInfo(index: Int): CameraServer.CaptureInfo {
        val position = telemetry?.position()
        val attitude = telemetry?.attitudeQuaternion()
        return CameraServer.CaptureInfo(
            position = CameraServer.Position(
                latitudeDeg = position?.latitudeDeg ?: Double.NaN,
                longitudeDeg = position?.longitudeDeg ?: Double.NaN,
                absoluteAltitudeM = position?.absoluteAltitudeM ?: Float.NaN,
                relativeAltitudeM = position?.relativeAltitudeM ?: Float.NaN,
            ),
            attitudeQuaternion = CameraServer.Quaternion(
                attitude?.w ?: 1f, attitude?.x ?: 0f, attitude?.y ?: 0f, attitude?.z ?: 0f),
            timeUtcUs = java.lang.System.currentTimeMillis() * 1000,
            isSuccess = true,
            index = index,
            fileUrl = "",
        )
    }

    private fun usedMib() = imageCount * IMAGE_MIB

    companion object {
        private const val TAG = "SimCamera"
        private const val CAPTURE_MS = 200L
        private const val IMAGE_MIB = 4f
        private const val STORAGE_MIB = 32f * 1024

        /** Loopback UDP port the vehicle sends its camera link to. */
        const val PORT = 14030
    }
}
