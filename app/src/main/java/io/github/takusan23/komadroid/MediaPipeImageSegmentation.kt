package io.github.takusan23.komadroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** MediaPipe で ImageSegmentation する */
class MediaPipeImageSegmentation(context: Context) {
    private val createInstanceTime = System.currentTimeMillis()
    private val _segmentedBitmapFlow = MutableStateFlow<Bitmap?>(null)

    private val imageSegmenter = ImageSegmenter.createFromOptions(
        context,
        ImageSegmenter.ImageSegmenterOptions.builder().apply {
            setBaseOptions(BaseOptions.builder().apply {
                // DeepLabV3
                // assets に置いたモデル
                setModelAssetPath("deeplab_v3.tflite")
            }.build())
            setRunningMode(RunningMode.LIVE_STREAM) // ライブモード、カメラ映像を流すため
            setOutputCategoryMask(true)
            setOutputConfidenceMasks(false)
            setResultListener { result, _ ->
                // Flow で通知
                _segmentedBitmapFlow.value = convertBitmapFromMPImage(result.categoryMask().get())
            }
        }.build()
    )

    /** 推論結果を流す Flow */
    val segmentedBitmapFlow = _segmentedBitmapFlow.asStateFlow()

    /** 推論して分類する。 */
    fun segmentation(bitmap: Bitmap) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        imageSegmenter.segmentAsync(mpImage, System.currentTimeMillis() - createInstanceTime)
    }

    /** 破棄する */
    fun destroy() {
        imageSegmenter.close()
    }

    /** [MPImage]から[Bitmap]を作る */
    private fun convertBitmapFromMPImage(mpImage: MPImage): Bitmap {
        val byteBuffer = ByteBufferExtractor.extract(mpImage)
        val pixels = IntArray(byteBuffer.capacity())

        for (i in pixels.indices) {
            // Using unsigned int here because selfie segmentation returns 0 or 255U (-1 signed)
            // with 0 being the found person, 255U for no label.
            // Deeplab uses 0 for background and other labels are 1-19,
            // so only providing 20 colors from ImageSegmenterHelper -> labelColors

            // 使ったモデル（DeepLab-v3）は、0 が背景。それ以外の 1 から 19 までが定義されているラベルになる
            // 今回は背景を青。それ以外は透過するようにしてみる。
            val index = byteBuffer.get(i).toUInt() % 20U
            val color = if (index.toInt() == 0) Color.BLUE else Color.TRANSPARENT
            pixels[i] = color
        }
        return Bitmap.createBitmap(
            pixels,
            mpImage.width,
            mpImage.height,
            Bitmap.Config.ARGB_8888
        )
    }
}