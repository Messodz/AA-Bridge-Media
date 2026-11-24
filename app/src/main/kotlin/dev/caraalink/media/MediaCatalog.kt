package dev.caraalink.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore

data class Track(
    val id: Long,
    val title: String,
    val artist: String?,
    val folder: String,
    val durationMs: Long,
    val uri: Uri,
)

data class Video(
    val id: Long,
    val title: String,
    val folder: String,
    val durationMs: Long,
    val uri: Uri,
)

data class RadioStation(val name: String, val url: String)

/** The phone's own music and videos (MediaStore), plus the user's radio list. */
object MediaCatalog {

    private const val CACHE_MS = 30_000L
    @Volatile private var tracksCache: Pair<Long, List<Track>>? = null
    @Volatile private var videosCache: Pair<Long, List<Video>>? = null

    fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun videoPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAudio(context: Context) =
        context.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED

    fun hasVideo(context: Context) =
        context.checkSelfPermission(videoPermission()) == PackageManager.PERMISSION_GRANTED

    fun invalidate() {
        tracksCache = null
        videosCache = null
    }

    fun tracks(context: Context): List<Track> {
        tracksCache?.let { (at, list) -> if (SystemClock.elapsedRealtime() - at < CACHE_MS) return list }
        if (!hasAudio(context)) return emptyList()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<Track>()
        runCatching {
            context.contentResolver.query(
                base,
                arrayOf("_id", "title", "artist", "duration", "relative_path"),
                "duration >= 20000",
                null,
                "title COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += Track(
                        id = id,
                        title = c.getString(1) ?: "Unknown",
                        artist = c.getString(2)?.takeUnless { it == "<unknown>" },
                        folder = (c.getString(4) ?: "").trimEnd('/').ifEmpty { "Music" },
                        durationMs = c.getLong(3),
                        uri = ContentUris.withAppendedId(base, id),
                    )
                }
            }
        }
        tracksCache = SystemClock.elapsedRealtime() to out
        return out
    }

    fun folders(context: Context): List<String> = tracks(context).map { it.folder }.distinct().sortedBy { it.lowercase() }

    fun tracksIn(context: Context, folder: String): List<Track> = tracks(context).filter { it.folder == folder }

    fun videos(context: Context): List<Video> {
        videosCache?.let { (at, list) -> if (SystemClock.elapsedRealtime() - at < CACHE_MS) return list }
        if (!hasVideo(context)) return emptyList()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<Video>()
        runCatching {
            context.contentResolver.query(
                base,
                arrayOf("_id", "title", "duration", "relative_path"),
                null,
                null,
                "date_added DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += Video(
                        id = id,
                        title = c.getString(1) ?: "Video",
                        durationMs = c.getLong(2),
                        folder = (c.getString(3) ?: "").trimEnd('/'),
                        uri = ContentUris.withAppendedId(base, id),
                    )
                }
            }
        }
        videosCache = SystemClock.elapsedRealtime() to out
        return out
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // --- Radio list, kept as "name<TAB>url" lines ---

    private fun prefs(context: Context) = context.getSharedPreferences("aa_media", Context.MODE_PRIVATE)

    fun radios(context: Context): List<RadioStation> =
        (prefs(context).getString("radio", null) ?: "")
            .lines()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size == 2 && parts[1].isNotBlank()) RadioStation(parts[0], parts[1]) else null
            }

    fun saveRadios(context: Context, list: List<RadioStation>) {
        prefs(context).edit()
            .putString("radio", list.joinToString("\n") { "${it.name.replace('\t', ' ')}\t${it.url.trim()}" })
            .apply()
    }

    fun addRadio(context: Context, name: String, url: String) {
        saveRadios(context, radios(context) + RadioStation(name.ifBlank { url }, url))
    }

    fun removeRadio(context: Context, index: Int) {
        saveRadios(context, radios(context).filterIndexed { i, _ -> i != index })
    }
}
