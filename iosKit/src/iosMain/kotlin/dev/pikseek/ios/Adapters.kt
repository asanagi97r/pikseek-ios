package dev.pikseek.ios

import dev.pikseek.auth.AuthHttpBridge
import dev.pikseek.auth.AuthHttpResult
import dev.pikseek.auth.KeychainReadResult
import dev.pikseek.player.MpvHandle
import dev.pikseek.player.MpvListener
import dev.pikseek.thumbnail.NativeFrameGrabber
import dev.pikseek.thumbnail.RawFrame
import platform.Foundation.NSData

// Swift 实现的接口 → 各模块自己的接口。逐个方法转过去，没有逻辑。

internal class AuthHttpAdapter(private val bridge: HttpBridge) : AuthHttpBridge {
    override fun post(url: String, headers: Map<String, String>, body: NSData): AuthHttpResult {
        val result = bridge.post(url, headers, body)
        return AuthHttpResult(result.status, result.body, result.failure, result.connectFailure)
    }
}

internal class KeychainAdapter(private val bridge: KeychainBridge) : dev.pikseek.auth.KeychainBridge {
    override fun write(name: String, data: NSData): Int = bridge.write(name, data)

    override fun read(name: String): KeychainReadResult = bridge.read(name).let { KeychainReadResult(it.data, it.status) }

    override fun delete(name: String) = bridge.delete(name)

    override fun names(): List<String> = bridge.names()
}

internal class MpvHandleAdapter(private val bridge: MpvPlayerBridge) : MpvHandle {
    override fun command(arguments: List<String>): Boolean = bridge.command(arguments)

    override fun setProperty(name: String, value: String): Boolean = bridge.setProperty(name, value)

    override fun getProperty(name: String): String? = bridge.getProperty(name)

    override fun observe(name: String) = bridge.observe(name)

    override fun setListener(listener: MpvListener?) {
        bridge.setEvents(
            listener?.let {
                object : MpvEvents {
                    override fun onPropertyChanged(name: String, value: String?) = it.onPropertyChanged(name, value)

                    override fun onEvent(event: String, detail: String?) = it.onEvent(event, detail)
                }
            },
        )
    }

    override fun close() = bridge.close()
}

internal class FrameGrabberAdapter(private val bridge: FrameGrabberBridge) : NativeFrameGrabber {
    override fun durationMs(): Long = bridge.durationMs()

    override fun open(location: String, timeoutMs: Long, startSeconds: Double): Boolean = bridge.open(location, timeoutMs, startSeconds)

    override fun grab(): RawFrame? = bridge.grab()?.toRaw()

    override fun seekAndGrab(seconds: Double, timeoutMs: Long): RawFrame? = bridge.seekAndGrab(seconds, timeoutMs)?.toRaw()

    override fun close() = bridge.close()

    private fun GrabbedFrame.toRaw() = RawFrame(timeMs, width, height, stride, data, rotate, displayWidth, displayHeight)
}
