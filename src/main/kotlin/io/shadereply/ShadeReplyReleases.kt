package io.shadereply

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

internal data class ShadeReplyRelease(
    val tag: String,
    val prerelease: Boolean,
    val notes: String,
    val page: String,
    val apk: String?,
)

internal object ShadeReplyReleases {
    const val PROJECT = "https://github.com/Xinhuaaa/shadereply"
    private const val API = "https://api.github.com/repos/Xinhuaaa/shadereply/releases?per_page=30"
    private val versionTag = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

    fun newerThan(installed: String): ShadeReplyRelease? {
        val local = version(installed) ?: throw IOException("Invalid installed version")
        val connection = URI.create(API).toURL().openConnection() as HttpURLConnection
        val releases = try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "ShadeReply/$installed")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONArray(it.readText()) }
        } finally {
            connection.disconnect()
        }
        val latest = (0 until releases.length()).asSequence()
            .map { releases.getJSONObject(it) }
            .filterNot { it.optBoolean("draft") }
            .mapNotNull { release -> version(release.optString("tag_name"))?.let { it to release } }
            .filter { (candidate, _) -> candidate > local }
            .maxByOrNull { (candidate, _) -> candidate }
            ?.second ?: return null
        val tag = latest.getString("tag_name")
        val assets = latest.optJSONArray("assets") ?: JSONArray()
        val apk = (0 until assets.length()).asSequence().map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            .map { it.optString("browser_download_url") }
            .firstOrNull { it.startsWith("$PROJECT/releases/download/$tag/") }
        return ShadeReplyRelease(
            tag, latest.optBoolean("prerelease"),
            latest.optString("body").take(12_000).trim(),
            "$PROJECT/releases/tag/$tag", apk,
        )
    }

    private fun version(value: String): Version? {
        val parts = versionTag.matchEntire(value)?.groupValues ?: return null
        val major = parts[1].toIntOrNull() ?: return null
        val minor = parts[2].toIntOrNull() ?: return null
        val patch = parts[3].toIntOrNull() ?: return null
        return Version(major, minor, patch)
    }

    private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    }
}
