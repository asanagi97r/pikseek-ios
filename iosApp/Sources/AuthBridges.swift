import Foundation
import Security
import PikSeekKit

/// 认证请求的传输层：系统的 URLSession，不带第三方网络库。
/// 去哪、发什么由 Kotlin 一侧的认证模块定（发之前已过白名单）；这里只管把一个 POST 发出去、把结果带回来。
/// 不跟随重定向，不存 Cookie、不留缓存，不打印请求与响应。
final class AuthHttp: NSObject, HttpBridge, URLSessionTaskDelegate {
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.timeoutIntervalForRequest = 60
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    func post(url: String, headers: [String: String], body: Data) -> HttpResult {
        guard let target = URL(string: url) else {
            return HttpResult(status: 0, body: "", failure: "地址无效", connectFailure: true)
        }
        var request = URLRequest(url: target)
        request.httpMethod = "POST"
        request.httpBody = body
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }

        let done = DispatchSemaphore(value: 0)
        var result = HttpResult(status: 0, body: "", failure: "没有响应", connectFailure: false)
        let task = session.dataTask(with: request) { data, response, error in
            if let error = error as NSError? {
                result = HttpResult(status: 0, body: "", failure: Self.describe(error), connectFailure: Self.neverSent(error))
            } else if let http = response as? HTTPURLResponse {
                let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
                result = HttpResult(status: Int32(http.statusCode), body: text, failure: nil, connectFailure: false)
            }
            done.signal()
        }
        task.resume()
        done.wait()
        return result
    }

    /// 服务端回 3xx 指向别处时不跟过去：请求体里有密码或刷新令牌。
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }

    /// 连接没建起来：请求一个字节也没发出去，重发不会重复生效。
    private static func neverSent(_ error: NSError) -> Bool {
        guard error.domain == NSURLErrorDomain else { return false }
        switch error.code {
        case NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost, NSURLErrorDNSLookupFailed,
             NSURLErrorNotConnectedToInternet, NSURLErrorSecureConnectionFailed:
            return true
        default:
            return false
        }
    }

    /// 只说是哪一类网络错误，不带地址。
    private static func describe(_ error: NSError) -> String {
        "网络错误（\(error.domain) \(error.code)）"
    }
}

/// 钥匙串里的登录会话。每一项只在这台设备上、解锁过一次之后可读，不随 iCloud 同步，也不会随备份恢复到别的设备。
/// 读回比对、存不了就不存这些规则在 Kotlin 一侧（KeychainCredentialStore）。
final class AuthKeychain: NSObject, KeychainBridge {
    private let service = "dev.pikseek.auth"

    private func query(_ name: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: false,
        ]
        if let name { query[kSecAttrAccount as String] = name }
        return query
    }

    func write(name: String, data: Data) -> Int32 {
        SecItemDelete(query(name) as CFDictionary)
        var item = query(name)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(item as CFDictionary, nil)
    }

    func read(name: String) -> KeychainItem {
        var item = query(name)
        item[kSecReturnData as String] = true
        item[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        let status = SecItemCopyMatching(item as CFDictionary, &found)
        return KeychainItem(data: status == errSecSuccess ? found as? Data : nil, status: status)
    }

    func delete(name: String) {
        SecItemDelete(query(name) as CFDictionary)
    }

    func names() -> [String] {
        var item = query(nil)
        item[kSecReturnAttributes as String] = true
        item[kSecMatchLimit as String] = kSecMatchLimitAll
        var found: CFTypeRef?
        guard SecItemCopyMatching(item as CFDictionary, &found) == errSecSuccess, let entries = found as? [[String: Any]] else { return [] }
        return entries.compactMap { $0[kSecAttrAccount as String] as? String }.sorted()
    }
}
