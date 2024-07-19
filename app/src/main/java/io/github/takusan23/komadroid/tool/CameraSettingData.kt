package io.github.takusan23.komadroid.tool

/** height / width は横画面時を想定。縦持ちの場合は入れ替えてください */
data class CameraSettingData(
    val frontCameraResolution: Resolution,
    val backCameraResolution: Resolution,
    val videoCodec: VideoCodec,
    val videoBitrate: Int,
    val cameraFps: Fps
) {

    enum class VideoCodec(val key: String) {
        AVC("avc"),
        HEVC("hevc");

        companion object {
            fun resolve(key: String) = entries.first { it.key == key }
        }
    }

    enum class Resolution(val key: String) {
        RESOLUTION_720P("720p"),
        RESOLUTION_1080P("1080p"),
        RESOLUTION_2160P("2160p");

        companion object {
            fun resolve(key: String) = entries.first { it.key == key }
        }
    }

    enum class Fps(val key: String) {
        FPS_30("30fps"),
        FPS_60("60fps");

        companion object {
            fun resolve(key: String) = Fps.entries.first { it.key == key }
        }
    }

    companion object {

        /** デフォルト設定 */
        val DEFAULT_SETTING = CameraSettingData(
            frontCameraResolution = CameraSettingData.Resolution.RESOLUTION_1080P,
            backCameraResolution = CameraSettingData.Resolution.RESOLUTION_1080P,
            videoCodec = CameraSettingData.VideoCodec.AVC,
            videoBitrate = 6_000_000,
            cameraFps = Fps.FPS_30
        )

    }
}