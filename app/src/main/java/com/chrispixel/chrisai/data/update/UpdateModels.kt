package com.chrispixel.chrisai.data.update

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String?,
    val apkUrl: String?,
    val checksumSha256: String?
) {
    val versionLabel: String get() = tagName.removePrefix("v").removePrefix("V")
}