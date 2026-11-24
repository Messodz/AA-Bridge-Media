package dev.caraalink.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * The audio side of the media hub: one player shared by Android Auto's own
 * media screen and the car-screen media panel. Shows up in Android Auto as
 * "AA Bridge Media" (Android Auto developer settings -> Unknown sources).
 *
 * Media IDs:
 *   root, all, folders, radio            browsable
 *   f|<folder>                           browsable folder
 *   t|<all or folder>|<id>               a track, queued with its list
 *   r|<index>                            a radio station
 */
@OptIn(UnstableApi::class)
class AaMediaService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var io: ListeningExecutorService

    override fun onCreate() {
        super.onCreate()
        io = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaLibrarySession.Builder(this, player, Callback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        io.shutdown()
        super.onDestroy()
    }

    private inner class Callback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(Library.folder(Library.ROOT, "AA Bridge Media"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = io.submit<LibraryResult<ImmutableList<MediaItem>>> {
            val all = Library.children(this@AaMediaService, parentId)
            val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
            val to = (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt()
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = io.submit<LibraryResult<MediaItem>> {
            Library.item(this@AaMediaService, mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = io.submit<MutableList<MediaItem>> {
            mediaItems.mapNotNull<MediaItem, MediaItem> { Library.item(this@AaMediaService, it.mediaId) ?: it.takeIf { m -> m.localConfiguration != null } }
                .toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = io.submit<MediaSession.MediaItemsWithStartPosition> {
            val ctx = this@AaMediaService
            if (mediaItems.size == 1) {
                val queue = Library.queueFor(ctx, mediaItems[0].mediaId)
                if (queue != null) {
                    return@submit MediaSession.MediaItemsWithStartPosition(queue.first, queue.second, startPositionMs)
                }
            }
            val resolved = mediaItems.mapNotNull<MediaItem, MediaItem> { Library.item(ctx, it.mediaId) ?: it.takeIf { m -> m.localConfiguration != null } }
            MediaSession.MediaItemsWithStartPosition(
                resolved,
                if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)),
                startPositionMs,
            )
        }
    }
}

/** Builds the browse tree and playable items; shared by the service and the car panel. */
object Library {
    const val ROOT = "root"
    const val ALL = "all"
    const val FOLDERS = "folders"
    const val RADIO = "radio"

    fun folder(id: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    fun trackId(context: String, track: Track) = "t|$context|${track.id}"

    fun trackItem(context: String, t: Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(trackId(context, t))
            .setUri(t.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setSubtitle(t.artist ?: t.folder.substringAfterLast('/'))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()

    fun radioItem(index: Int, r: RadioStation): MediaItem =
        MediaItem.Builder()
            .setMediaId("r|$index")
            .setUri(Uri.parse(r.url))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(r.name)
                    .setSubtitle("Radio")
                    .setStation(r.name)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build(),
            )
            .build()

    fun children(ctx: Context, parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(ALL, "All songs"),
            folder(FOLDERS, "Folders"),
            folder(RADIO, "Radio"),
        )
        parentId == ALL -> MediaCatalog.tracks(ctx).map { trackItem(ALL, it) }
        parentId == FOLDERS -> MediaCatalog.folders(ctx).map { f ->
            folder("f|$f", f.substringAfterLast('/'), f)
        }
        parentId.startsWith("f|") -> {
            val f = parentId.removePrefix("f|")
            MediaCatalog.tracksIn(ctx, f).map { trackItem(f, it) }
        }
        parentId == RADIO -> MediaCatalog.radios(ctx).mapIndexed { i, r -> radioItem(i, r) }
        else -> emptyList()
    }

    fun item(ctx: Context, mediaId: String): MediaItem? = when {
        mediaId == ROOT || mediaId == ALL || mediaId == FOLDERS || mediaId == RADIO ->
            children(ctx, ROOT).firstOrNull { it.mediaId == mediaId } ?: folder(ROOT, "AA Bridge Media")
        mediaId.startsWith("f|") -> folder(mediaId, mediaId.removePrefix("f|").substringAfterLast('/'))
        mediaId.startsWith("t|") -> {
            val listCtx = mediaId.substringAfter("t|").substringBeforeLast('|')
            val id = mediaId.substringAfterLast('|').toLongOrNull()
            MediaCatalog.tracks(ctx).firstOrNull { it.id == id }?.let { trackItem(listCtx, it) }
        }
        mediaId.startsWith("r|") -> {
            val i = mediaId.removePrefix("r|").toIntOrNull()
            MediaCatalog.radios(ctx).getOrNull(i ?: -1)?.let { radioItem(i!!, it) }
        }
        else -> null
    }

    /** A track plays with the rest of its list after it; returns the queue and start index. */
    fun queueFor(ctx: Context, mediaId: String): Pair<List<MediaItem>, Int>? {
        if (!mediaId.startsWith("t|")) return null
        val listCtx = mediaId.substringAfter("t|").substringBeforeLast('|')
        val id = mediaId.substringAfterLast('|').toLongOrNull() ?: return null
        val list = if (listCtx == ALL) MediaCatalog.tracks(ctx) else MediaCatalog.tracksIn(ctx, listCtx)
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        return list.map { trackItem(listCtx, it) } to index
    }
}
