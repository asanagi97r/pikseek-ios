package dev.pikseek.auth

actual object CredentialStoreText {
    actual val signInNote: String = "登录会话经 Windows DPAPI 加密后保存在本机，仅当前 Windows 用户可解。"
    actual val storage: String = "Windows DPAPI（当前用户）加密后存于数据目录"
    actual val reportLabel: String = "session stored with DPAPI"
}
