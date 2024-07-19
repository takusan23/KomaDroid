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
        context.dataStore.edit { settings ->
            settings[KEY_FRONT_CAMERA_RESOLUTION] = settingData.frontCameraResolution.key
            settings[KEY_BACK_CAMERA_RESOLUTION] = settingData.backCameraResolution.key
            settings[KEY_VIDEO_CODEC] = settingData.videoCodec.key
            settings[KEY_VIDEO_BITRATE] = settingData.videoBitrate
            settings[KEY_CAMERA_FPS] = settingData.cameraFps.key
        }
    }
}
