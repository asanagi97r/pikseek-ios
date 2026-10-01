package dev.piko.desktop

import dev.piko.data.auth.StoredPikoPreferences

/** PikoUserPreferences 的桌面实现：全部写穿到数据目录下的 settings.properties。逻辑在共用的 [StoredPikoPreferences] 里。 */
class DesktopPikoPreferences(settings: DesktopSettingsStore) : StoredPikoPreferences(
    settings = settings,
    defaultDownloadDirectory = { settings.downloadDirectory.absolutePath },
    onDownloadDirectoryChanged = settings::setDownloadDirectory,
)
