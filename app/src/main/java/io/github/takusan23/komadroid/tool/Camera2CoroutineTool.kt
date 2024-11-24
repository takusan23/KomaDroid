package io.github.takusan23.komadroid.tool

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.view.Surface
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Camera2 API のコールバックをサスペンド関数にしたもの

/**
 * カメラを開く
 * 開くのに成功したら[CameraDevice]を流します。失敗したら null を流します。
 *
 * @param cameraId 起動したいカメラ
 * @param executor [java.util.concurrent.Executors.newSingleThreadExecutor]とか
 */
@SuppressLint("MissingPermission")
fun CameraManager.openCameraFlow(
    cameraId: String,
    executor: Executor
) = callbackFlow {
    var _cameraDevice: CameraDevice? = null
    openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            _cameraDevice = camera
            trySend(CameraDeviceState.Open(camera))
        }

        override fun onDisconnected(camera: CameraDevice) {
            trySend(CameraDeviceState.Disconnect)
            _cameraDevice = camera
            camera.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            trySend(CameraDeviceState.Error)
            _cameraDevice = camera
            camera.close()
        }
    })
    awaitClose { _cameraDevice?.close() }
}

/** カメラの状態。Open / Disconnect / Error */
sealed interface CameraDeviceState {
    data class Open(val cameraDevice: CameraDevice) : CameraDeviceState
    data object Disconnect : CameraDeviceState
    data object Error : CameraDeviceState
}

/**
 * [SessionConfiguration]が非同期なので、コルーチンで出来るように。
 * 構成に失敗した場合は[SessionConfigureFailedException]を投げます。
 * Android も[android.hardware.camera2.CameraAccessException]を投げてきます。（android.hardware.camera2.CameraAccessException: CAMERA_ERROR (3): createStream:1021）
 *
 * @param outputSurfaceList 出力先[Surface]
 * @param executor [java.util.concurrent.Executors.newSingleThreadExecutor]とか
 * @param isEnableTenBitHdr 10Bit HDR 動画撮影をする場合。[isTenBitProfileSupported]が true の場合は利用可能です。HLG 形式になります。
 */
suspend fun CameraDevice.awaitCameraSessionConfiguration(
    outputSurfaceList: List<Surface>,
    executor: Executor,
    isEnableTenBitHdr: Boolean
) = suspendCancellableCoroutine { continuation ->
    // OutputConfiguration を作る
    val outputConfigurationList = outputSurfaceList
        .map { surface -> OutputConfiguration(surface) }
        .onEach { outputConfig ->
            // 10Bit HDR 動画撮影する場合
            if (isEnableTenBitHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                outputConfig.dynamicRangeProfile = DynamicRangeProfiles.HLG10
            }
        }
    val sessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputConfigurationList, executor, object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(captureSession: CameraCaptureSession) {
            continuation.resume(captureSession)
        }

        override fun onConfigureFailed(p0: CameraCaptureSession) {
            continuation.resumeWithException(SessionConfigureFailedException())
        }
    })
    createCaptureSession(sessionConfiguration)
}

/** [CameraCaptureSession.StateCallback.onConfigureFailed]時に投げる例外 */
class SessionConfigureFailedException() : RuntimeException()

/**
 * TODO 置き場がないのでとりあえずここで、、
 *
 * Camera2 API で 10Bit HDR をサポートしているか。
 * Android 13 以上で、Camera2 API （サードパーティカメラアプリ）でも 10Bit HDR を使える場合。
 *
 * 10Bit HDR 動画撮影をサポートしている場合、少なくとも HLG 形式での HDR に対応しているそう。
 * https://developer.android.com/media/camera/camera2/hdr-video-capture#resources
 *
 * @return 10Bit HDR 動画撮影に対応している場合は true
 */
fun CameraManager.isTenBitProfileSupported(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    cameraIdList
        .map { cameraId -> getCameraCharacteristics(cameraId) }
        .map { cameraCharacteristics -> cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) }
        .any { capabilities -> capabilities?.any { it == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT } == true }
} else false