package io.github.takusan23.komadroid.tool

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Android DataStore */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object DataStoreTool {

    // TODO 極端に低いビットレートは回避する旨を表示する
    // 配信サイトの推奨エンコード設定に従う
    private const val MIN_BITRATE_AVC_HD = 6_000_000
    private const val MIN_BITRATE_AVC_FHD = 12_000_000
    private const val MIN_BITRATE_AVC_UHD = 35_000_000

    // HEVC が使えるならその半分にする
    private const val MIN_BITRATE_HEVC_HD = MIN_BITRATE_AVC_HD / 2
    private const val MIN_BITRATE_HEVC_FHD = MIN_BITRATE_AVC_FHD / 2
    private const val MIN_BITRATE_HEVC_UHD = MIN_BITRATE_AVC_UHD / 2

    private val KEY_FRONT_CAMERA_RESOLUTION = stringPreferencesKey("front_camera_resolution")
    private val KEY_BACK_CAMERA_RESOLUTION = stringPreferencesKey("back_camera_resolution")
    private val KEY_VIDEO_CODEC = stringPreferencesKey("video_codec")
    private val KEY_VIDEO_BITRATE = intPreferencesKey("video_bitrate")
    private val KEY_CAMERA_FPS = stringPreferencesKey("camera_fps")

    suspend fun readData(context: Context) = withContext(Dispatchers.IO) {
        val preferences = context.dataStore.data.first()
        CameraSettingData(
            frontCameraResolution = preferences[KEY_FRONT_CAMERA_RESOLUTION]?.let { CameraSettingData.Resolution.resolve(it) } ?: CameraSettingData.DEFAULT_SETTING.frontCameraResolution,
            backCameraResolution = preferences[KEY_BACK_CAMERA_RESOLUTION]?.let { CameraSettingData.Resolution.resolve(it) } ?: CameraSettingData.DEFAULT_SETTING.backCameraResolution,
            videoCodec = preferences[KEY_VIDEO_CODEC]?.let { CameraSettingData.VideoCodec.resolve(it) } ?: CameraSettingData.DEFAULT_SETTING.videoCodec,
            videoBitrate = preferences[KEY_VIDEO_BITRATE] ?: CameraSettingData.DEFAULT_SETTING.videoBitrate,
            cameraFps = preferences[KEY_CAMERA_FPS]?.let { CameraSettingData.Fps.resolve(it) } ?: CameraSettingData.DEFAULT_SETTING.cameraFps
        )
    }

    suspend fun writeData(context: Context, settingData: CameraSettingData) = withContext(Dispatchers.IO) {

        // 極端に低いビットレートは回避する
        val fixBitrate = when (settingData.videoCodec) {
            CameraSettingData.VideoCodec.AVC -> when (settingData.highestResolution) {
                CameraSettingData.Resolution.RESOLUTION_720P -> MIN_BITRATE_AVC_HD
                CameraSettingData.Resolution.RESOLUTION_1080P -> MIN_BITRATE_AVC_FHD
                CameraSettingData.Resolution.RESOLUTION_2160P -> MIN_BITRATE_AVC_UHD
            }

            CameraSettingData.VideoCodec.HEVC -> when (settingData.highestResolution) {
                CameraSettingData.Resolution.RESOLUTION_720P -> MIN_BITRATE_HEVC_HD
                CameraSettingData.Resolution.RESOLUTION_1080P -> MIN_BITRATE_HEVC_FHD
                CameraSettingData.Resolution.RESOLUTION_2160P -> MIN_BITRATE_HEVC_UHD
            }
        }.coerceAtLeast(settingData.videoBitrate)

        context.dataStore.edit { settings ->
            settings[KEY_FRONT_CAMERA_RESOLUTION] = settingData.frontCameraResolution.key
            settings[KEY_BACK_CAMERA_RESOLUTION] = settingData.backCameraResolution.key
            settings[KEY_VIDEO_CODEC] = settingData.videoCodec.key
            settings[KEY_VIDEO_BITRATE] = fixBitrate
            settings[KEY_CAMERA_FPS] = settingData.cameraFps.key
        }
    }
}
