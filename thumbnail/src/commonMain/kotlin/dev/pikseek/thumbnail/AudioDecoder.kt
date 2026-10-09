package dev.pikseek.thumbnail

/**
 * 把一段视频里的声音解成单声道 16 位 PCM，认片头片尾时用。与取帧一样是一个不出画面、不出声音的独立解码器，
 * 不碰主播放器。桌面是经 FFM 直调的 libmpv（MpvAudioDecoder），iOS 是 Swift 一侧包的 libmpv。
 *
 * 方法会阻塞，调用方放在后台线程上。
 */
fun interface AudioDecoder {
    /**
     * 解 [location]（本机路径）的声音：从 [startSeconds] 起解 [lengthSeconds] 秒，null 表示从头、到尾。
     * 混成单声道、重采样到 [sampleRate] Hz。解不出来或超过 [timeoutMs] 时为 null。
     */
    fun decode(location: String, sampleRate: Int, startSeconds: Double?, lengthSeconds: Double?, timeoutMs: Long): ShortArray?
}
