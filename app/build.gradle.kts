dependencies {
    // 1. 核心推流與 MediaCodec 硬體編碼架構 (支援 RTMP / 錄影 / 音訊延遲 / OpenGL)
    implementation("com.github.pedroSG94.RootEncoder:library:2.4.5")
    
    // 2. UVC 採集卡直連與多路 HDMI 輸入 (支援多攝影機同時開啟)
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.3.3")
    
    // 基礎 UI 與協程
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
