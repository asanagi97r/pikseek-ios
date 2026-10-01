package dev.pikseek.player

/**
 * 一个 libmpv 实例，只露出播放后端用得到的几样：下命令、读写属性、订阅属性变化、收事件。
 *
 * iOS 上由 Swift 一侧实现（MPVKit 的 libmpv，画面经 Metal 层输出）；桌面的测试里有一份经 FFM 直调的实现，
 * 拿真的 libmpv 把 [MpvPlaybackBackend] 的逻辑跑一遍。画面输出怎么建、建实例之前要设的选项，都是实现的事。
 *
 * 方法可以从任意线程调；libmpv 的客户端接口本身是线程安全的。
 */
interface MpvHandle {
    /** `mpv_command`。返回是否被接受；被接受不等于做成了，结果从事件里看。 */
    fun command(arguments: List<String>): Boolean

    /** `mpv_set_property_string`。 */
    fun setProperty(name: String, value: String): Boolean

    /** `mpv_get_property_string`。属性眼下不可用时为 null。标志位读出来是 yes / no，数字是十进制字符串。 */
    fun getProperty(name: String): String?

    /** 此后 [name] 每变一次，[MpvListener.onPropertyChanged] 就被调一次（订阅时也会先报一次当前值）。 */
    fun observe(name: String)

    /** 设了之后事件才开始送。传 null 停止。 */
    fun setListener(listener: MpvListener?)

    /** 销毁实例。之后别的方法都不该再调。 */
    fun close()
}

/** 回调在实现自己的事件线程上，不在界面线程上。 */
interface MpvListener {
    /** [value] 与 [MpvHandle.getProperty] 同样的写法；属性变得不可用时为 null。 */
    fun onPropertyChanged(name: String, value: String?)

    /**
     * [event] 是 `mpv_event_name` 给的名字：start-file、file-loaded、playback-restart、seek、end-file、shutdown。
     * end-file 的 [detail] 是原因：eof、stop、quit、redirect，出错时是 `error:<mpv 的错误说明>`。其余事件为 null。
     */
    fun onEvent(event: String, detail: String?)
}
