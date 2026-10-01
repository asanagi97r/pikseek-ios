package dev.pikseek.auth

/**
 * 这个平台上登录会话存在哪，给界面与安全报告用的几句话。桌面是 Windows DPAPI，iOS 是钥匙串。
 */
expect object CredentialStoreText {
    /** 登录页的一句说明。 */
    val signInNote: String

    /** 安全页「存储方式」的写法，后面接会话个数。 */
    val storage: String

    /** 安全报告里那一行的名字，例如 `session stored with DPAPI`。 */
    val reportLabel: String
}
