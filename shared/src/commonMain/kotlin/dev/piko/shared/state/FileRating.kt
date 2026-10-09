package dev.piko.shared.state

/**
 * PikSeek：对一个文件的态度。收藏就是网盘的星标；讨厌记在 PikSeek 自己的名单里（见 dev.pikseek.ui.rating.FileRatings）。
 * 网盘页按它筛选：只看收藏、只看讨厌、只看还没表态的。
 */
enum class FileRating(val label: String) {
    NONE("未标记"),
    LIKED("收藏"),
    DISLIKED("讨厌"),
}
