package io.github.takusan23.komadroid.tool

/**
 * height / width は横画面時を想定。縦持ちの場合は入れ替えてください
 *
 * @param frontCameraResolution フロントカメラの解像度
 * @param backCameraResolution バックカメラの解像度
 * @param videoCodec 動画撮影のコーデック
 * @param videoBitrate 動画撮影のビットレート
 * @param cameraFps 動画撮影のフレームレート
 * @param isTenBitHdr 動画を 10Bit HDR で撮影する場合
 */
data class CameraSettingData(
    val frontCameraResolution: Resolution,
    val backCameraResolution: Resolution,
    val videoCodec: VideoCodec,
    val videoBitrate: Int,
    val cameraFps: Fps,
    val isTenBitHdr: Boolean
) {

    /** フロント、バックカメラの解像度のうち、どちらか大きい方を返す */
    val highestResolution: Resolution
        get() = listOf(frontCameraResolution, backCameraResolution).maxBy { it.ordinal }

    enum class VideoCodec(val key: String) {
        AVC("avc"),
        HEVC("hevc");

        companion object {
            fun resolve(key: String) = entries.first { it.key == key }
        }
    }

    enum class Resolution(val key: String, val width: Int, val height: Int) {
        RESOLUTION_720P("720p", 1280, 720),
        RESOLUTION_1080P("1080p", 1920, 1080),
        RESOLUTION_2160P("2160p", 3840, 2160);

        /** 横画面用 */
        val landscape: Size
            get() = Size(width = width, height = height)

        /** 縦画面用 */
        val portrait: Size
            get() = Size(width = height, height = width)

        companion object {
            fun resolve(key: String) = entries.first { it.key == key }
        }

    }

    enum class Fps(val key: String, val fps: Int) {
        FPS_30("30fps", 30),
        FPS_60("60fps", 60);

        companion object {
            fun resolve(key: String) = Fps.entries.first { it.key == key }
        }
    }

    data class Size(val width: Int, val height: Int)

    companion object {

        /** デフォルト設定 */
        val DEFAULT_SETTING = CameraSettingData(
            frontCameraResolution = CameraSettingData.Resolution.RESOLUTION_1080P,
            backCameraResolution = CameraSettingData.Resolution.RESOLUTION_1080P,
            videoCodec = CameraSettingData.VideoCodec.AVC,
            videoBitrate = 12_000_000,
            cameraFps = Fps.FPS_30,
            isTenBitHdr = false
        )

    }
}