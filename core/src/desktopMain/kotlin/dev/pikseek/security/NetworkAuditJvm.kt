package dev.pikseek.security

import java.net.URI

/** 给 ProxySelector 用：从它拿到的 URI 里取主机名。 */
fun NetworkAudit.record(uri: URI) {
    record(uri.host)
}
