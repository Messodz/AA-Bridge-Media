package dev.caraalink.media

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

/**
 * Fermata-style media hub drawn straight onto the car screen, on top of
 * Android Auto: music folders, radio, parked-only video and a parked-only
 * web browser. Audio goes through [AaMediaService], so Android Auto's own
 * media screen shows and controls the same playback.
 */
@OptIn(UnstableApi::class)
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled", "SetTextI18n")
class CarMediaPanel(
    context: Context,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    private enum class Tab { MUSIC, VIDEO, RADIO, WEB }

    private data class Row(val title: String, val subtitle: String?, val action: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val rows = ArrayList<Row>()
    private val adapter = RowAdapter()
    private val list = ListView(context)
    private val content = FrameLayout(context)
    private val videoBox = FrameLayout(context)
    private val webBox = LinearLayout(context)
    private val lock = TextView(context)
    private val nowTitle = TextView(context)
    private val playPause = TextView(context)
    private val navButtons = HashMap<Tab, TextView>()

    private var tab = Tab.MUSIC
    private var videoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var web: WebView? = null
    private var urlField: EditText? = null
    private var webPausedByLock = false
    private var released = false

    init {
        setBackgroundColor(BG)
        isClickable = true // nothing falls through to Android Auto underneath

        val rootRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        // Left: tabs
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NAV_BG)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun navItem(t: Tab, label: String) {
            val b = button(label) { select(t) }
            navButtons[t] = b
            nav.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { bottomMargin = dp(8) })
        }
        navItem(Tab.MUSIC, "♫  Music")
        navItem(Tab.VIDEO, "▶  Videos")
        navItem(Tab.RADIO, "📻  Radio")
        navItem(Tab.WEB, "🌐  Web")
        nav.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        nav.addView(
            button("◀  Android Auto", ACCENT) { onClose() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)),
        )
        rootRow.addView(nav, LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.MATCH_PARENT))

        // Right: content + now-playing bar
        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        list.adapter = adapter
        list.divider = null
        list.setOnItemClickListener { _, _, position, _ -> rows.getOrNull(position)?.action?.invoke() }
        content.addView(list, match())
        buildVideoBox()
        content.addView(videoBox, match())
        buildWebBox()
        content.addView(webBox, match())
        lock.apply {
            setBackgroundColor(0xEE000000.toInt())
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
            isClickable = true
        }
        content.addView(lock, match())
        right.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        right.addView(buildNowPlaying(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)))
        rootRow.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        addView(rootRow, match())
        DrivingState.start(context)
        select(Tab.MUSIC)
    }

    // --- lifecycle ---

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, AaMediaService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (released) return@addListener
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = updateNowPlaying()
            })
            updateNowPlaying()
        }, ContextCompat.getMainExecutor(context))
        main.post(lockTick)
    }

    override fun onDetachedFromWindow() {
        released = true
        main.removeCallbacksAndMessages(null)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        playerView?.player = null
        videoPlayer?.release()
        videoPlayer = null
        web?.destroy()
        web = null
        io.shutdown()
        super.onDetachedFromWindow()
    }

    private val lockTick = object : Runnable {
        override fun run() {
            if (released) return
            val needsParking = (tab == Tab.VIDEO && videoBox.visibility == View.VISIBLE) || tab == Tab.WEB
            val locked = needsParking && !DrivingState.isParked()
            if (locked) {
                videoPlayer?.pause()
                if (web != null && !webPausedByLock) {
                    web?.onPause()
                    webPausedByLock = true
                }
                lock.text = "🚗  ${DrivingState.reason()}\n\nMusic and radio keep working."
                lock.visibility = View.VISIBLE
            } else {
                lock.visibility = View.GONE
                if (webPausedByLock) {
                    web?.onResume()
                    webPausedByLock = false
                }
            }
            main.postDelayed(this, 1000)
        }
    }

    // --- tabs ---

    private fun select(t: Tab) {
        tab = t
        navButtons.forEach { (k, b) -> (b.background as GradientDrawable).setColor(if (k == t) ACCENT else BUTTON) }
        if (t != Tab.VIDEO) closeVideo()
        list.visibility = if (t == Tab.WEB) View.GONE else View.VISIBLE
        webBox.visibility = if (t == Tab.WEB) View.VISIBLE else View.GONE
        when (t) {
            Tab.MUSIC -> showMusicTop()
            Tab.VIDEO -> showVideos()
            Tab.RADIO -> showRadio()
            Tab.WEB -> ensureWeb()
        }
    }

    private fun load(producer: () -> List<Row>) {
        setRows(listOf(Row("Loading…", null) {}))
        io.execute {
            val r = runCatching(producer).getOrElse { listOf(Row("Couldn't read the library", it.message) {}) }
            main.post { if (!released) setRows(r) }
        }
    }

    private fun setRows(r: List<Row>) {
        rows.clear()
        rows.addAll(r)
        adapter.notifyDataSetChanged()
        list.setSelection(0)
    }

    private fun showMusicTop(): Unit = load {
        val ctx = context
        if (!MediaCatalog.hasAudio(ctx)) {
            return@load listOf(Row("Music access needed", "Open AA Media on the phone and tap Allow") {})
        }
        val out = ArrayList<Row>()
        out += Row("All songs", "${MediaCatalog.tracks(ctx).size} songs") { showTracks(Library.ALL, "All songs") }
        MediaCatalog.folders(ctx).forEach { f ->
            out += Row("📁  " + f.substringAfterLast('/'), f) { showTracks(f, f.substringAfterLast('/')) }
        }
        if (out.size == 1 && MediaCatalog.tracks(ctx).isEmpty()) out += Row("No music found on the phone", null) {}
        out
    }

    private fun showTracks(listCtx: String, title: String): Unit = load {
        val ctx = context
        val tracks = if (listCtx == Library.ALL) MediaCatalog.tracks(ctx) else MediaCatalog.tracksIn(ctx, listCtx)
        listOf(Row("⬆  Back", title) { showMusicTop() }) + tracks.map { t ->
            Row(t.title, listOfNotNull(t.artist, MediaCatalog.formatDuration(t.durationMs).ifEmpty { null }).joinToString("  ·  ")) {
                playAudio(Library.trackId(listCtx, t))
            }
        }
    }

    private fun showRadio(): Unit = load {
        val radios = MediaCatalog.radios(context)
        if (radios.isEmpty()) {
            listOf(Row("No stations yet", "Add stream links in the AA Media app on the phone") {})
        } else {
            radios.mapIndexed { i, r -> Row("📻  " + r.name, r.url) { playAudio("r|$i") } }
        }
    }

    private fun showVideos(): Unit = load {
        val ctx = context
        if (!MediaCatalog.hasVideo(ctx)) {
            return@load listOf(Row("Video access needed", "Open AA Media on the phone and tap Allow") {})
        }
        val videos = MediaCatalog.videos(ctx)
        if (videos.isEmpty()) return@load listOf(Row("No videos found on the phone", null) {})
        videos.map { v ->
            Row(v.title, listOf(v.folder.substringAfterLast('/'), MediaCatalog.formatDuration(v.durationMs)).filter { it.isNotEmpty() }.joinToString("  ·  ")) {
                playVideo(v)
            }
        }
    }

    // --- audio ---

    private fun playAudio(mediaId: String) {
        closeVideo()
        val c = controller ?: return
        c.setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
        c.prepare()
        c.play()
    }

    private fun buildNowPlaying(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(NAV_BG)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        nowTitle.apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "Nothing playing"
        }
        bar.addView(nowTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val size = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = dp(8) }
        bar.addView(button("⏮") { controller?.seekToPreviousMediaItem() }, size)
        playPause.also {
            styleButton(it, "⏯", BUTTON)
            it.setOnClickListener { controller?.let { c -> if (c.isPlaying) c.pause() else c.play() } }
        }
        bar.addView(playPause, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = dp(8) })
        bar.addView(button("⏭") { controller?.seekToNextMediaItem() }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = dp(8) })
        return bar
    }

    private fun updateNowPlaying() {
        val c = controller ?: return
        val md: MediaMetadata = c.mediaMetadata
        val title = md.title?.toString()
        nowTitle.text = when {
            title == null -> "Nothing playing"
            md.artist != null -> "$title  —  ${md.artist}"
            else -> title
        }
        playPause.text = if (c.isPlaying) "⏸" else "▶"
    }

    // --- video (parked only) ---

    private fun buildVideoBox() {
        videoBox.setBackgroundColor(Color.BLACK)
        videoBox.visibility = View.GONE
        val pv = PlayerView(context).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        playerView = pv
        videoBox.addView(pv, match())
        videoBox.addView(
            button("✕  Close", 0xCC333333.toInt()) { closeVideo() },
            LayoutParams(dp(150), dp(56), Gravity.TOP or Gravity.END).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) },
        )
    }

    private fun playVideo(v: Video) {
        controller?.pause()
        val p = videoPlayer ?: ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
            .also { videoPlayer = it }
        playerView?.player = p
        p.setMediaItem(MediaItem.fromUri(v.uri))
        p.prepare()
        p.play()
        list.visibility = View.GONE
        videoBox.visibility = View.VISIBLE
        main.removeCallbacks(lockTick)
        main.post(lockTick)
    }

    private fun closeVideo() {
        videoPlayer?.stop()
        if (videoBox.visibility == View.VISIBLE) {
            videoBox.visibility = View.GONE
            if (tab != Tab.WEB) list.visibility = View.VISIBLE
        }
    }

    // --- web (parked only) ---

    private fun buildWebBox() {
        webBox.orientation = LinearLayout.VERTICAL
        webBox.visibility = View.GONE
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val field = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Search or type a web address"
            textSize = 20f
            setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    go(v.text.toString()); true
                } else false
            }
        }
        urlField = field
        bar.addView(field, LinearLayout.LayoutParams(0, dp(56), 1f))
        val p = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { leftMargin = dp(8) } }
        bar.addView(button("Go") { go(field.text.toString()) }, p())
        bar.addView(button("◀") { web?.let { if (it.canGoBack()) it.goBack() } }, p())
        bar.addView(button("YouTube") { go("https://m.youtube.com") }, p())
        bar.addView(button("Google") { go("https://www.google.com") }, p())
        webBox.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun ensureWeb() {
        if (web != null) return
        val w = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    urlField?.setText(url)
                }
            }
            webChromeClient = WebChromeClient()
        }
        web = w
        webBox.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        w.loadUrl("https://www.google.com")
    }

    private fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            text.contains('.') && !text.contains(' ') -> "https://$text"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(text, "UTF-8")
        }
        ensureWeb()
        web?.loadUrl(url)
    }

    // --- small view helpers ---

    private fun match() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun styleButton(b: TextView, label: String, color: Int) {
        b.text = label
        b.setTextColor(Color.WHITE)
        b.textSize = 20f
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        b.setPadding(dp(14), 0, dp(14), 0)
        b.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(color)
        }
        b.isClickable = true
    }

    private fun button(label: String, color: Int = BUTTON, onClick: () -> Unit): TextView =
        TextView(context).apply {
            styleButton(this, label, color)
            setOnClickListener { onClick() }
        }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                minimumHeight = dp(68)
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    setTextColor(0xFFB0B8C0.toInt())
                    textSize = 16f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            val row = rows[position]
            (holder.getChildAt(0) as TextView).text = row.title
            (holder.getChildAt(1) as TextView).apply {
                text = row.subtitle ?: ""
                visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            holder.setBackgroundColor(if (position % 2 == 0) BG else ROW_ALT)
            return holder
        }
    }

    private companion object {
        val BG = 0xFF101418.toInt()
        val ROW_ALT = 0xFF161B21.toInt()
        val NAV_BG = 0xFF0A0D10.toInt()
        val BUTTON = 0xFF2A323C.toInt()
        val ACCENT = 0xFF1E88E5.toInt()
    }
}
