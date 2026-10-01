package dev.piko.shared.text

/**
 * 正则里一个分组在原文中的位置。JVM 与 Native 的 MatchGroup 都有 range，只是各平台共用的声明里没有它。
 */
expect val MatchGroup.groupRange: IntRange
