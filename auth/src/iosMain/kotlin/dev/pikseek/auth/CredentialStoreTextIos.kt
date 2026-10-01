package dev.pikseek.auth

actual object CredentialStoreText {
    actual val signInNote: String = "登录会话保存在 iOS 钥匙串里，只在这台设备上可读，不随 iCloud 同步。"
    actual val storage: String = "iOS 钥匙串（仅本机，不随 iCloud 同步）"
    actual val reportLabel: String = "session stored in Keychain"
}
