package io.github.takusan23.komadroid.gl2

object EffectFragmentShaderExample {

    /**
     * ぼかし
     * thx!!!!!!!!
     * https://github.com/GameMakerDiscord/blur-shaders
     */
    const val FRAGMENT_SHADER_BLUR = """
precision mediump float;

uniform vec2 vResolution;
uniform vec4 vCropLocation;
uniform sampler2D sVideoFrameTexture;

const int Quality = 8;
const int Directions = 16;
const float Pi = 6.28318530718; //pi * 2
const float Radius = 32.0; // ぼかし具合

void main() {
  // テクスチャ座標に変換
  vec2 vTextureCoord = gl_FragCoord.xy / vResolution.xy;

    vec2 radius = Radius / vResolution.xy;
    vec4 Color = texture2D(sVideoFrameTexture, vTextureCoord);
    
    if (vCropLocation[0] < vTextureCoord.x && vCropLocation[1] > vTextureCoord.x && vCropLocation[2] < vTextureCoord.y && vCropLocation[3] > vTextureCoord.y) {
        for( float d=0.0;d<Pi;d+=Pi/float(Directions) )
        {
            for( float i=1.0/float(Quality);i<=1.0;i+=1.0/float(Quality) )
            {
                Color += texture2D(sVideoFrameTexture, vTextureCoord+vec2(cos(d),sin(d))*radius*i);
            }
        }
        Color /= float(Quality)*float(Directions)+1.0;
    }
    
    gl_FragColor = Color;
    
}
"""

    /** モザイク */
    const val FRAGMENT_SHADER_MOSAIC = """
precision mediump float;

uniform vec2 vResolution;
uniform vec4 vCropLocation;
uniform sampler2D sVideoFrameTexture;

void main() {
  // テクスチャ座標に変換
  vec2 vTextureCoord = gl_FragCoord.xy / vResolution.xy;
  // 出力色
  vec4 outColor = vec4(1.);

  // 範囲内だけ
  if (vCropLocation[0] < vTextureCoord.x && vCropLocation[1] > vTextureCoord.x && vCropLocation[2] < vTextureCoord.y && vCropLocation[3] > vTextureCoord.y) {
    // モザイクしてみる
    vTextureCoord = floor(vTextureCoord * 15.0) / 15.0;
    outColor = texture2D(sVideoFrameTexture, vTextureCoord);
  } else {
    outColor = texture2D(sVideoFrameTexture, vTextureCoord);
  }

  // 出力
  gl_FragColor = outColor;
}
"""

}