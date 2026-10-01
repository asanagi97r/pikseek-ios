package dev.pikseek.platform

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** 桌面版的设置文件：数据目录下的 pikseek.properties。 */
class PropertiesSettingsStore(private val file: Path) : SettingsStore {
    override fun load(): Map<String, String> {
        val properties = Properties()
        if (Files.isRegularFile(file)) runCatching { Files.newInputStream(file).use(properties::load) }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }

    override fun save(values: Map<String, String>) {
        val properties = Properties()
        values.forEach { (key, value) -> properties.setProperty(key, value) }
        runCatching {
            Files.createDirectories(file.parent)
            Files.newOutputStream(file).use { properties.store(it, "PikSeek settings (no secrets)") }
        }
    }
}

/** 桌面版的设置，存成数据目录下的 pikseek.properties。 */
fun AppSettings(file: Path = AppPaths.dataRoot.resolve("pikseek.properties")): AppSettings =
    AppSettings(PropertiesSettingsStore(file))
