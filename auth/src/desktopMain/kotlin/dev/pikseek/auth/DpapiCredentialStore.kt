package dev.pikseek.auth

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Arrays
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Windows DPAPI（CryptProtectData，CurrentUser 范围）加密后存成文件，每项一个 `<名字>.dpapi`。
 *
 * 密钥由 Windows 按「当前登录用户」保管，程序自己不持有任何密钥：换一个 Windows 用户、换一台电脑，
 * 或者把文件拷走，都解不开。没有 CRYPTPROTECT_LOCAL_MACHINE，同一台电脑上的其他用户也解不开。
 *
 * 加密失败、写入失败、读回对不上时一律抛 [IOException] 并且不留文件。**没有明文兜底。**
 *
 * 直接经 JDK 的 FFM 调 crypt32.dll，不起 PowerShell，不依赖第三方库。
 */
class DpapiCredentialStore(private val directory: Path) : SecureCredentialStore {
    private val unavailable: String? by lazy { probe() }

    override fun problem(): String? = unavailable

    override fun write(name: String, secret: ByteArray) {
        unavailable?.let { throw IOException(it) }
        val target = fileOf(name)
        val blob = Dpapi.protect(secret)
        try {
            Files.createDirectories(directory)
            val staging = Files.createTempFile(directory, "w", ".tmp")
            try {
                Files.write(staging, blob)
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                staging.deleteIfExists()
            }
            // 读回核对：磁盘上的东西确实解得开、确实是刚才那份，才算存下了
            val stored = Dpapi.unprotect(Files.readAllBytes(target))
            val intact = stored.contentEquals(secret)
            Arrays.fill(stored, 0)
            if (!intact) throw IOException("DPAPI 读回的内容与写入的不一致")
        } catch (e: Exception) {
            runCatching { target.deleteIfExists() }
            throw e as? IOException ?: IOException("保存失败：${e.message}", e)
        }
    }

    override fun read(name: String): ByteArray? {
        val file = fileOf(name)
        if (!file.isRegularFile()) return null
        unavailable?.let { throw IOException(it) }
        return Dpapi.unprotect(Files.readAllBytes(file))
    }

    override fun delete(name: String) {
        runCatching { fileOf(name).deleteIfExists() }
    }

    override fun names(): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.newDirectoryStream(directory, "*$EXTENSION").use { files ->
            files.map { it.name.removeSuffix(EXTENSION) }.sorted()
        }
    }

    private fun fileOf(name: String): Path {
        require(NAME.matches(name)) { "非法的存储名" }
        return directory.resolve(name + EXTENSION)
    }

    /** 当场加密再解密一小段，确认 DPAPI 在这台机器、这个用户下真的能用。 */
    private fun probe(): String? {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return "这个系统没有 DPAPI，登录信息不会保存"
        return try {
            val sample = "pikseek-dpapi-probe".toByteArray()
            if (Dpapi.unprotect(Dpapi.protect(sample)).contentEquals(sample)) null else "DPAPI 自检未通过，登录信息不会保存"
        } catch (e: Throwable) {
            "DPAPI 不可用（${e.message ?: e::class.simpleName}），登录信息不会保存"
        }
    }

    private companion object {
        const val EXTENSION = ".dpapi"
        val NAME = Regex("[a-z0-9_-]{1,64}")
    }
}

/** crypt32 的 CryptProtectData / CryptUnprotectData，经 FFM 直调。 */
internal object Dpapi {
    // typedef struct { DWORD cbData; BYTE *pbData; } DATA_BLOB; 64 位上 cbData 后有 4 字节对齐
    private val BLOB: StructLayout = MemoryLayout.structLayout(
        JAVA_INT.withName("cbData"),
        MemoryLayout.paddingLayout(4),
        ADDRESS.withName("pbData"),
    )
    private const val OFFSET_SIZE = 0L
    private const val OFFSET_DATA = 8L

    /** 不许弹任何界面：加解密失败就失败，不让系统弹框问用户。 */
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

    // 附加熵：不知道它的程序即便以同一用户身份调 CryptUnprotectData 也解不开。它不是密钥，只是把密文与本程序绑在一起
    private val ENTROPY = "PikSeek.auth.v1".toByteArray(Charsets.UTF_8)

    private val linker = Linker.nativeLinker()
    private val errorState = Linker.Option.captureCallState("GetLastError")
    private val errorLayout = Linker.Option.captureStateLayout()
    private val lastError = errorLayout.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"))

    private val crypt32: SymbolLookup by lazy { SymbolLookup.libraryLookup("crypt32", Arena.global()) }
    private val kernel32: SymbolLookup by lazy { SymbolLookup.libraryLookup("kernel32", Arena.global()) }

    // BOOL CryptProtectData(DATA_BLOB* in, LPCWSTR description, DATA_BLOB* entropy, PVOID reserved,
    //                       CRYPTPROTECT_PROMPTSTRUCT* prompt, DWORD flags, DATA_BLOB* out)
    private val cryptProtectData: MethodHandle by lazy {
        linker.downcallHandle(
            crypt32.find("CryptProtectData").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS),
            errorState,
        )
    }

    // BOOL CryptUnprotectData(DATA_BLOB* in, LPWSTR* description, DATA_BLOB* entropy, PVOID reserved,
    //                         CRYPTPROTECT_PROMPTSTRUCT* prompt, DWORD flags, DATA_BLOB* out)
    private val cryptUnprotectData: MethodHandle by lazy {
        linker.downcallHandle(
            crypt32.find("CryptUnprotectData").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS),
            errorState,
        )
    }

    // HLOCAL LocalFree(HLOCAL)
    private val localFree: MethodHandle by lazy {
        linker.downcallHandle(kernel32.find("LocalFree").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS))
    }

    fun protect(plain: ByteArray): ByteArray = transform(plain, protecting = true)

    fun unprotect(blob: ByteArray): ByteArray = transform(blob, protecting = false)

    private fun transform(input: ByteArray, protecting: Boolean): ByteArray {
        Arena.ofConfined().use { arena ->
            val inputBytes = arena.allocate(maxOf(input.size, 1).toLong())
            MemorySegment.copy(input, 0, inputBytes, JAVA_BYTE, 0, input.size)
            val entropyBytes = arena.allocate(ENTROPY.size.toLong())
            MemorySegment.copy(ENTROPY, 0, entropyBytes, JAVA_BYTE, 0, ENTROPY.size)
            val inBlob = blobOf(arena, inputBytes, input.size)
            val entropyBlob = blobOf(arena, entropyBytes, ENTROPY.size)
            val outBlob = arena.allocate(BLOB)
            val state = arena.allocate(errorLayout)
            try {
                val handle = if (protecting) cryptProtectData else cryptUnprotectData
                val succeeded = handle.invoke(
                    state,
                    inBlob,
                    MemorySegment.NULL,
                    entropyBlob,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    CRYPTPROTECT_UI_FORBIDDEN,
                    outBlob,
                ) as Int
                if (succeeded == 0) {
                    val code = lastError.get(state, 0L) as Int
                    throw IOException(
                        if (protecting) "DPAPI 加密失败（错误 $code）" else "DPAPI 解不开这份数据（错误 $code）：它不是当前 Windows 用户在这台电脑上存的",
                    )
                }
                val size = outBlob.get(JAVA_INT, OFFSET_SIZE)
                val pointer = outBlob.get(ADDRESS, OFFSET_DATA)
                try {
                    val data = pointer.reinterpret(size.toLong())
                    val result = data.toArray(JAVA_BYTE)
                    // 解出来的明文在系统分配的内存里，还回去之前抹掉
                    if (!protecting) data.fill(0)
                    return result
                } finally {
                    localFree.invoke(pointer) as MemorySegment
                }
            } finally {
                // 加密时输入是明文
                if (protecting) inputBytes.fill(0)
            }
        }
    }

    private fun blobOf(arena: Arena, data: MemorySegment, size: Int): MemorySegment =
        arena.allocate(BLOB).also {
            it.set(JAVA_INT, OFFSET_SIZE, size)
            it.set(ADDRESS, OFFSET_DATA, data)
        }
}
